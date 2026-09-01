package io.aegiscloud.controlplane.k8s;

import io.aegiscloud.controlplane.persistence.ClusterEntity;
import io.aegiscloud.controlplane.persistence.ClusterRepository;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeCondition;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.VersionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 3's Deployment Engine: cluster registration, connectivity, and workload
 * rollout, all through the standard Kubernetes API.
 *
 * <p>Nothing here knows which cloud it is talking to. The provider recorded against
 * a cluster is a label used for grouping and reporting; the code path that deploys
 * to EKS is byte-for-byte the code path that deploys to a laptop's kind cluster.
 */
@Service
public class DeploymentEngine {

    private static final Logger log = LoggerFactory.getLogger(DeploymentEngine.class);

    /** Applied to everything the platform creates, so its objects can be told from hand-made ones. */
    private static final String MANAGED_BY_LABEL = "app.kubernetes.io/managed-by";
    private static final String MANAGED_BY_VALUE = "aegiscloud";

    /**
     * The resource request every platform-deployed container carries.
     *
     * <p>Not a tuning default but a prerequisite: CPU utilisation is measured against
     * the request, so a container that asks for nothing can never be autoscaled on
     * CPU, and the scheduler has nothing to place it by. Phase 4's scaling engine
     * reports such a workload as unmeasurable rather than guessing, which is exactly
     * the situation this avoids.
     */
    private static final String DEFAULT_CPU_REQUEST = "100m";
    private static final String DEFAULT_MEMORY_REQUEST = "128Mi";

    private final KubernetesClientFactory clients;
    private final ClusterRepository clusters;

    public DeploymentEngine(KubernetesClientFactory clients, ClusterRepository clusters) {
        this.clients = clients;
        this.clusters = clusters;
    }

    /**
     * Contacts a cluster and records what was observed.
     *
     * <p>An unreachable cluster is a normal outcome, not an error: it is precisely
     * the condition the platform exists to notice. The cluster row is updated either
     * way so the fleet view reflects reality rather than the last good reading.
     */
    @Transactional
    public ClusterConnectivity probe(UUID clusterId) {
        ClusterEntity cluster = clusters.findById(clusterId)
                .orElseThrow(() -> new IllegalArgumentException("no such cluster: " + clusterId));

        ClusterConnectivity result = probeCluster(cluster);

        if (result.reachable()) {
            cluster.markReachable(result.nodeCount(), result.k8sVersion());
        } else {
            cluster.markUnreachable();
        }
        clusters.save(cluster);

        return result;
    }

    private ClusterConnectivity probeCluster(ClusterEntity cluster) {
        if (cluster.getKubeconfigRef() == null || cluster.getKubeconfigRef().isBlank()) {
            return ClusterConnectivity.unreachable("no kubeconfig context configured for this cluster");
        }

        try (KubernetesClient client = clients.clientFor(cluster.getKubeconfigRef())) {
            List<Node> nodes = client.nodes().list().getItems();
            long ready = nodes.stream().filter(DeploymentEngine::isNodeReady).count();

            String version = serverVersion(client, nodes);

            log.info("cluster {} reachable: {}/{} nodes ready, {}",
                    cluster.getName(), ready, nodes.size(), version);
            return ClusterConnectivity.reachable(nodes.size(), (int) ready, version);

        } catch (Exception e) {
            log.info("cluster {} unreachable: {}", cluster.getName(), e.getMessage());
            return ClusterConnectivity.unreachable(rootMessage(e));
        }
    }

    /**
     * Reads the cluster's version, preferring the API server's own report.
     *
     * <p>Falls back to the kubelet version off a node when that call fails. The
     * {@code /version} payload gains fields as Kubernetes advances, and a client
     * built against an older release can reject one it does not recognise — a
     * cosmetic mismatch that must not be allowed to report a perfectly healthy
     * cluster as unreachable.
     */
    private static String serverVersion(KubernetesClient client, List<Node> nodes) {
        try {
            VersionInfo info = client.getKubernetesVersion();
            if (info != null && info.getGitVersion() != null) {
                return info.getGitVersion();
            }
        } catch (Exception e) {
            log.debug("server version unavailable, falling back to kubelet version: {}", e.getMessage());
        }

        return nodes.stream()
                .filter(n -> n.getStatus() != null && n.getStatus().getNodeInfo() != null)
                .map(n -> n.getStatus().getNodeInfo().getKubeletVersion())
                .filter(v -> v != null && !v.isBlank())
                .findFirst()
                .orElse("unknown");
    }

    private static boolean isNodeReady(Node node) {
        if (node.getStatus() == null || node.getStatus().getConditions() == null) {
            return false;
        }
        return node.getStatus().getConditions().stream()
                .filter(c -> "Ready".equals(c.getType()))
                .map(NodeCondition::getStatus)
                .anyMatch("True"::equals);
    }

    /**
     * Rolls a workload out to a cluster, creating the namespace if it is missing.
     *
     * <p>Server-side apply is used rather than create-or-replace so that repeating a
     * deployment converges instead of failing, and so fields the platform does not
     * manage (anything a human or another controller set) are left alone.
     */
    public DeploymentOutcome deploy(String clusterName, String namespace, String workloadName,
                                    String image, int replicas, int containerPort, boolean adopt) {

        ClusterEntity cluster = clusters.findByName(clusterName)
                .orElseThrow(() -> new IllegalArgumentException("no such cluster: " + clusterName));

        try (KubernetesClient client = clients.clientFor(cluster.getKubeconfigRef())) {
            ensureNamespace(client, namespace);

            Deployment existing = client.apps().deployments()
                    .inNamespace(namespace).withName(workloadName).get();

            if (existing != null && !isManagedByPlatform(existing) && !adopt) {
                // Refusing here is the whole point. Server-side apply merges list
                // entries by key, so applying over a deployment somebody else wrote
                // does not replace its containers — it adds ours alongside theirs,
                // producing a pod that binds the same port twice and crash-loops.
                // Silently mangling a workload the platform did not create is a far
                // worse failure than declining to touch it.
                return new DeploymentOutcome(false, workloadName, namespace, clusterName, replicas, 0,
                        "refusing to modify '" + workloadName + "': it exists but is not managed by "
                                + "AegisCloud. Re-send with adopt=true to take ownership of it.");
            }

            Deployment deployment = buildDeployment(namespace, workloadName, image, replicas, containerPort);

            if (existing != null && !isManagedByPlatform(existing)) {
                // Adoption replaces the spec outright rather than merging into it,
                // so the platform ends up with exactly the workload it described
                // instead of a union with whatever was there before.
                deployment.getMetadata().setResourceVersion(
                        existing.getMetadata().getResourceVersion());
                client.apps().deployments().inNamespace(namespace).resource(deployment).update();
                log.info("adopted pre-existing deployment {}/{}", namespace, workloadName);
            } else {
                client.apps().deployments()
                        .inNamespace(namespace)
                        .resource(deployment)
                        .forceConflicts()
                        .serverSideApply();
            }

            Deployment applied = client.apps().deployments()
                    .inNamespace(namespace).withName(workloadName).get();

            int readyReplicas = Optional.ofNullable(applied)
                    .map(Deployment::getStatus)
                    .map(s -> s.getReadyReplicas() == null ? 0 : s.getReadyReplicas())
                    .orElse(0);

            log.info("deployed {}/{} to {} ({} ready of {})",
                    namespace, workloadName, clusterName, readyReplicas, replicas);

            return new DeploymentOutcome(true, workloadName, namespace, clusterName,
                    replicas, readyReplicas, "applied");

        } catch (Exception e) {
            log.warn("deploy of {}/{} to {} failed: {}", namespace, workloadName, clusterName, e.getMessage());
            return new DeploymentOutcome(false, workloadName, namespace, clusterName,
                    replicas, 0, rootMessage(e));
        }
    }

    /** Reads the live state of a workload the platform deployed. */
    public DeploymentOutcome status(String clusterName, String namespace, String workloadName) {
        ClusterEntity cluster = clusters.findByName(clusterName)
                .orElseThrow(() -> new IllegalArgumentException("no such cluster: " + clusterName));

        try (KubernetesClient client = clients.clientFor(cluster.getKubeconfigRef())) {
            Deployment deployment = client.apps().deployments()
                    .inNamespace(namespace).withName(workloadName).get();

            if (deployment == null) {
                return new DeploymentOutcome(false, workloadName, namespace, clusterName, 0, 0,
                        "not found");
            }

            int desired = Optional.ofNullable(deployment.getSpec().getReplicas()).orElse(0);
            int ready = Optional.ofNullable(deployment.getStatus())
                    .map(s -> s.getReadyReplicas() == null ? 0 : s.getReadyReplicas())
                    .orElse(0);

            return new DeploymentOutcome(true, workloadName, namespace, clusterName, desired, ready,
                    ready == desired ? "healthy" : "progressing");

        } catch (Exception e) {
            return new DeploymentOutcome(false, workloadName, namespace, clusterName, 0, 0,
                    rootMessage(e));
        }
    }

    /** True when this deployment carries the platform's management label. */
    private static boolean isManagedByPlatform(Deployment deployment) {
        Map<String, String> labels = deployment.getMetadata().getLabels();
        return labels != null && MANAGED_BY_VALUE.equals(labels.get(MANAGED_BY_LABEL));
    }

    private void ensureNamespace(KubernetesClient client, String namespace) {
        if (client.namespaces().withName(namespace).get() != null) {
            return;
        }
        Namespace ns = new NamespaceBuilder()
                .withNewMetadata()
                .withName(namespace)
                .addToLabels(MANAGED_BY_LABEL, MANAGED_BY_VALUE)
                .endMetadata()
                .build();
        client.namespaces().resource(ns).create();
        log.info("created namespace {}", namespace);
    }

    private Deployment buildDeployment(String namespace, String name, String image,
                                       int replicas, int containerPort) {
        Map<String, String> selector = Map.of("app", name);

        return new DeploymentBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(namespace)
                .addToLabels(MANAGED_BY_LABEL, MANAGED_BY_VALUE)
                .addToLabels("app", name)
                .endMetadata()
                .withNewSpec()
                .withReplicas(replicas)
                .withNewSelector().withMatchLabels(selector).endSelector()
                .withNewTemplate()
                .withNewMetadata().addToLabels(selector).endMetadata()
                .withNewSpec()
                .addNewContainer()
                .withName(name)
                .withImage(image)
                .addNewPort().withContainerPort(containerPort).endPort()
                .withNewResources()
                .addToRequests("cpu", new io.fabric8.kubernetes.api.model.Quantity(DEFAULT_CPU_REQUEST))
                .addToRequests("memory", new io.fabric8.kubernetes.api.model.Quantity(DEFAULT_MEMORY_REQUEST))
                .endResources()
                // Probes are what make self-healing possible in Phase 4: without a
                // readiness signal the platform cannot tell a starting pod from a
                // broken one.
                .withNewReadinessProbe()
                .withNewHttpGet().withPath("/healthz").withNewPort(containerPort).endHttpGet()
                .withInitialDelaySeconds(3)
                .withPeriodSeconds(5)
                .endReadinessProbe()
                .endContainer()
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
    }

    /** Kubernetes failures nest several layers deep; the innermost message is the useful one. */
    private static String rootMessage(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null ? current.getClass().getSimpleName() : message;
    }

    /** The result of a deploy or status read. */
    public record DeploymentOutcome(
            boolean succeeded,
            String workload,
            String namespace,
            String cluster,
            int desiredReplicas,
            int readyReplicas,
            String detail) {
    }
}
