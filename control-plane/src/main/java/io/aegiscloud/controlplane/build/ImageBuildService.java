package io.aegiscloud.controlplane.build;

import io.aegiscloud.controlplane.audit.AuditLog;
import io.aegiscloud.controlplane.k8s.KubernetesClientFactory;
import io.aegiscloud.controlplane.persistence.ClusterEntity;
import io.aegiscloud.controlplane.persistence.ClusterRepository;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds container images from a Git repository and pushes them to a registry.
 *
 * <p>The build runs as a Kubernetes Job using Kaniko, which matters for one reason:
 * it needs no Docker daemon and no privileged access. The obvious alternative — the
 * control plane shelling out to {@code docker build} — would mean mounting a Docker
 * socket into the platform, and a socket is root on the host. A component whose whole
 * purpose is to be trusted with production clusters should not also be the component
 * holding the key to the machine it runs on.
 *
 * <p>It also keeps the architectural rule intact. The build is a Kubernetes object
 * like everything else, so the same code builds on kind and on EKS, and a build's
 * progress is visible to anyone with cluster access rather than only inside the
 * platform's own logs.
 */
@Service
public class ImageBuildService {

    private static final Logger log = LoggerFactory.getLogger(ImageBuildService.class);

    /**
     * Kaniko, pinned. A build tool that silently changes version between builds
     * makes "it built yesterday" an unanswerable question.
     */
    private static final String KANIKO_IMAGE = "gcr.io/kaniko-project/executor:v1.23.2";

    /** Where build Jobs run. Separate from the workloads they produce. */
    private static final String BUILD_NAMESPACE = "aegiscloud-builds";

    private final KubernetesClientFactory clients;
    private final ClusterRepository clusters;
    private final BuildStore store;
    private final AuditLog audit;
    private final String registry;
    private final boolean registryInsecure;

    public ImageBuildService(KubernetesClientFactory clients, ClusterRepository clusters,
                             BuildStore store, AuditLog audit,
                             @Value("${aegiscloud.registry.url:}") String registry,
                             @Value("${aegiscloud.registry.insecure:true}") boolean registryInsecure) {
        this.clients = clients;
        this.clusters = clusters;
        this.store = store;
        this.audit = audit;
        this.registry = registry == null ? "" : registry.replaceAll("/$", "");
        this.registryInsecure = registryInsecure;
    }

    public boolean registryConfigured() {
        return !registry.isBlank();
    }

    public String registry() {
        return registry;
    }

    /**
     * @param gitUrl      a repository Kaniko can clone. Public over HTTPS today: build
     *                    credentials are a secrets-management problem the platform has
     *                    not solved, and pretending otherwise by accepting a token in
     *                    a request body would be worse than the limitation.
     * @param contextPath the directory inside the repository holding the Dockerfile
     */
    public record BuildRequest(
            UUID serviceId,
            String clusterName,
            String gitUrl,
            String gitRef,
            String contextPath,
            String dockerfile,
            String imageName,
            String tag) {
    }

    public record BuildStarted(String buildId, String image, String jobName, String detail) {
    }

    /**
     * Starts a build and returns immediately.
     *
     * <p>Builds take minutes. An HTTP call that waited would time out long before the
     * image existed, leaving the caller unable to tell a slow build from a failed one,
     * so the build is recorded first and followed by its id.
     */
    public BuildStarted start(BuildRequest request) {
        if (!registryConfigured()) {
            throw new IllegalStateException(
                    "no image registry is configured; set aegiscloud.registry.url");
        }

        ClusterEntity cluster = clusters.findByName(request.clusterName())
                .orElseThrow(() -> new IllegalArgumentException(
                        "no such cluster: " + request.clusterName()));

        String tag = request.tag() == null || request.tag().isBlank()
                ? "build-" + System.currentTimeMillis() / 1000 : request.tag();
        String image = registry + "/" + request.imageName() + ":" + tag;

        String jobName = ("build-" + request.imageName().replaceAll("[^a-z0-9-]", "-")
                + "-" + tag).toLowerCase(Locale.ROOT);
        if (jobName.length() > 60) {
            jobName = jobName.substring(0, 60);
        }

        UUID buildId = store.open(request.serviceId(), cluster.getId(), request.gitUrl(),
                request.gitRef(), request.contextPath(), request.dockerfile(), image, jobName);

        try (KubernetesClient client = clients.clientFor(cluster.getKubeconfigRef())) {
            ensureNamespace(client);
            client.batch().v1().jobs().inNamespace(BUILD_NAMESPACE)
                    .resource(buildJob(jobName, request, image)).create();

            log.info("build {} started: {} from {}", buildId, image, request.gitUrl());

        } catch (Exception e) {
            store.finish(buildId, "FAILED", "could not start the build job: " + rootMessage(e));
            throw new IllegalStateException("could not start the build: " + rootMessage(e), e);
        }

        audit.recordUserAction("BUILD_IMAGE", "image_build", buildId.toString(),
                Map.of("image", image, "gitUrl", request.gitUrl(), "ref", request.gitRef()));

        return new BuildStarted(buildId.toString(), image, jobName,
                "build running; poll /api/v1/builds/" + buildId);
    }

    private Job buildJob(String jobName, BuildRequest request, String image) {
        String context = "git://" + request.gitUrl().replaceFirst("^https?://", "")
                + "#refs/heads/" + request.gitRef();

        List<String> args = new java.util.ArrayList<>(List.of(
                "--context=" + context,
                "--context-sub-path=" + request.contextPath(),
                "--dockerfile=" + request.dockerfile(),
                "--destination=" + image,
                // Kaniko caches layers in the registry; without this every build
                // re-downloads and re-runs every step, which turns a small change
                // into a full rebuild.
                "--cache=true",
                "--cache-repo=" + registry + "/cache"));

        if (registryInsecure) {
            // A local registry speaks plain HTTP. Stated as configuration rather
            // than assumed, so a real deployment against a TLS registry does not
            // silently keep pushing in the clear.
            args.add("--insecure");
            args.add("--skip-tls-verify");
            args.add("--insecure-pull");
        }

        return new JobBuilder()
                .withNewMetadata()
                .withName(jobName)
                .withNamespace(BUILD_NAMESPACE)
                .addToLabels("app.kubernetes.io/managed-by", "aegiscloud")
                .addToLabels("aegiscloud.io/component", "image-build")
                .endMetadata()
                .withNewSpec()
                // One attempt. A build that failed because the Dockerfile is wrong
                // fails identically three times, and the retries only delay the
                // report an operator is waiting for.
                .withBackoffLimit(0)
                .withTtlSecondsAfterFinished(3600)
                .withNewTemplate()
                .withNewSpec()
                .withRestartPolicy("Never")
                .addNewContainer()
                .withName("kaniko")
                .withImage(KANIKO_IMAGE)
                .withArgs(args)
                .endContainer()
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
    }

    private void ensureNamespace(KubernetesClient client) {
        if (client.namespaces().withName(BUILD_NAMESPACE).get() == null) {
            client.namespaces().resource(new io.fabric8.kubernetes.api.model.NamespaceBuilder()
                    .withNewMetadata().withName(BUILD_NAMESPACE)
                    .addToLabels("app.kubernetes.io/managed-by", "aegiscloud")
                    .endMetadata().build()).create();
        }
    }

    /**
     * Follows running builds to completion.
     *
     * <p>Polled rather than watched because a build finishing is not urgent — nothing
     * reacts to it automatically — and a watch on Jobs would be a second informer to
     * keep alive for a signal checked once a minute.
     */
    @Scheduled(initialDelayString = "${aegiscloud.build.initial-delay-ms:30000}",
            fixedDelayString = "${aegiscloud.build.poll-ms:15000}")
    public void followRunningBuilds() {
        for (BuildStore.RunningBuild build : store.running()) {
            try {
                clusters.findById(build.clusterId()).ifPresent(cluster -> follow(build, cluster));
            } catch (Exception e) {
                log.debug("could not follow build {}: {}", build.id(), e.getMessage());
            }
        }
    }

    private void follow(BuildStore.RunningBuild build, ClusterEntity cluster) {
        try (KubernetesClient client = clients.clientFor(cluster.getKubeconfigRef())) {
            Job job = client.batch().v1().jobs()
                    .inNamespace(BUILD_NAMESPACE).withName(build.jobName()).get();

            if (job == null) {
                // The Job's TTL removed it before the platform noticed it finish.
                // Recorded as failed rather than left running forever: the honest
                // statement is that the outcome is unknown, and an unknown build
                // must not be treated as a success.
                store.finish(build.id(), "FAILED",
                        "the build job no longer exists; its outcome was not observed");
                return;
            }

            Integer succeeded = job.getStatus() == null ? null : job.getStatus().getSucceeded();
            Integer failed = job.getStatus() == null ? null : job.getStatus().getFailed();

            if (succeeded != null && succeeded > 0) {
                store.finish(build.id(), "SUCCEEDED", "image pushed to the registry");
                log.info("build {} succeeded", build.id());
            } else if (failed != null && failed > 0) {
                store.finish(build.id(), "FAILED", lastLogLine(client, build.jobName()));
                log.info("build {} failed", build.id());
            }
        }
    }

    /** The tail of the build log, which is where the reason a build failed lives. */
    private String lastLogLine(KubernetesClient client, String jobName) {
        try {
            String logs = client.batch().v1().jobs()
                    .inNamespace(BUILD_NAMESPACE).withName(jobName).getLog();

            if (logs == null || logs.isBlank()) {
                return "build failed; no log output";
            }
            String[] lines = logs.strip().split("\n");
            int from = Math.max(0, lines.length - 5);
            return String.join(" | ", java.util.Arrays.copyOfRange(lines, from, lines.length));

        } catch (Exception e) {
            return "build failed; logs unavailable: " + rootMessage(e);
        }
    }

    /** Build logs, for a caller inspecting a build. */
    public Optional<String> logs(UUID buildId) {
        Optional<BuildStore.BuildRow> build = store.build(buildId);
        if (build.isEmpty() || build.get().jobName() == null) {
            return Optional.empty();
        }
        String jobName = build.get().jobName();

        return store.clusterOf(buildId)
                .flatMap(clusters::findById)
                .map(cluster -> {
                    try (KubernetesClient client = clients.clientFor(cluster.getKubeconfigRef())) {
                        return client.batch().v1().jobs()
                                .inNamespace(BUILD_NAMESPACE).withName(jobName).getLog();
                    } catch (Exception e) {
                        return "logs unavailable: " + rootMessage(e);
                    }
                });
    }

    private static String rootMessage(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null ? current.getClass().getSimpleName() : message;
    }
}
