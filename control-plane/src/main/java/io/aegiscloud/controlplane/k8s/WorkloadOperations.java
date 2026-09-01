package io.aegiscloud.controlplane.k8s;

import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodCondition;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.ContainerMetrics;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetrics;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Live reads and the two write operations the Phase 4 engines need: change a
 * workload's replica count, and delete a pod.
 *
 * <p>Separate from {@link DeploymentEngine} because rollout is a deliberate,
 * operator-initiated act while these are what the autonomous loop performs on its
 * own. Keeping them apart makes it obvious, from the imports alone, which code can
 * act unattended.
 *
 * <p>Like everything else that touches a cluster, this goes through the standard
 * Kubernetes API only — the same calls reach kind and EKS.
 */
@Component
public class WorkloadOperations {

    private static final Logger log = LoggerFactory.getLogger(WorkloadOperations.class);

    private final KubernetesClientFactory clients;

    public WorkloadOperations(KubernetesClientFactory clients) {
        this.clients = clients;
    }

    /**
     * Everything the engines get to reason about for one workload, read in a single
     * pass so that the scaling and healing decisions in a cycle are made against the
     * same snapshot rather than two slightly different ones.
     *
     * @param cpuUtilizationPct absent when metrics-server is not installed, or when
     *                          the containers declare no CPU request — utilisation
     *                          against an undeclared request is not a number, and
     *                          inventing one would drive real scaling decisions.
     */
    public record WorkloadObservation(
            boolean found,
            int desiredReplicas,
            int readyReplicas,
            List<PodObservation> pods,
            OptionalDouble cpuUtilizationPct,
            String detail) {

        /** Ready pods over desired, as a percentage; the loop's cheapest health signal. */
        public double readyPct() {
            return desiredReplicas <= 0 ? 0 : (readyReplicas * 100.0) / desiredReplicas;
        }

        static WorkloadObservation missing(String detail) {
            return new WorkloadObservation(false, 0, 0, List.of(), OptionalDouble.empty(), detail);
        }
    }

    /**
     * @param reason the container-level waiting or termination reason as Kubernetes
     *               reports it (CrashLoopBackOff, OOMKilled, ...), or the pod-level
     *               scheduling reason. Empty when the pod is fine.
     */
    public record PodObservation(
            String name,
            String phase,
            boolean ready,
            int restarts,
            String reason) {
    }

    /** Reads the current state of a workload and its pods. */
    public WorkloadObservation observe(String kubeContext, String namespace, String workload) {
        try (KubernetesClient client = clients.clientFor(kubeContext)) {
            Deployment deployment = client.apps().deployments()
                    .inNamespace(namespace).withName(workload).get();

            if (deployment == null) {
                return WorkloadObservation.missing("workload " + namespace + "/" + workload + " not found");
            }

            int desired = Optional.ofNullable(deployment.getSpec().getReplicas()).orElse(0);
            int ready = Optional.ofNullable(deployment.getStatus())
                    .map(s -> s.getReadyReplicas() == null ? 0 : s.getReadyReplicas())
                    .orElse(0);

            Map<String, String> selector = deployment.getSpec().getSelector().getMatchLabels();
            List<Pod> pods = client.pods().inNamespace(namespace).withLabels(selector).list().getItems();

            List<PodObservation> observed = pods.stream().map(WorkloadOperations::describe).toList();

            return new WorkloadObservation(true, desired, ready, observed,
                    cpuUtilization(client, namespace, pods),
                    ready == desired ? "healthy" : "degraded");

        } catch (Exception e) {
            return WorkloadObservation.missing(rootMessage(e));
        }
    }

    /**
     * CPU usage across the workload's pods as a percentage of what they requested.
     *
     * <p>Requests are the denominator rather than node capacity because that is what
     * the scheduler and every HPA use: a pod at 100% of its request is at the size it
     * asked to be, regardless of how large the node underneath it happens to be.
     */
    private static OptionalDouble cpuUtilization(KubernetesClient client, String namespace, List<Pod> pods) {
        if (pods.isEmpty()) {
            return OptionalDouble.empty();
        }

        double requestedMillis = pods.stream()
                .flatMap(p -> p.getSpec().getContainers().stream())
                .filter(c -> c.getResources() != null && c.getResources().getRequests() != null)
                .map(c -> c.getResources().getRequests().get("cpu"))
                .filter(Objects::nonNull)
                .mapToDouble(WorkloadOperations::milliCores)
                .sum();

        if (requestedMillis <= 0) {
            // No request declared, so there is no baseline to be a percentage of.
            return OptionalDouble.empty();
        }

        Map<String, Double> usageByPod = new HashMap<>();
        try {
            for (PodMetrics metrics : client.top().pods().metrics(namespace).getItems()) {
                double used = metrics.getContainers().stream()
                        .map(ContainerMetrics::getUsage)
                        .filter(Objects::nonNull)
                        .map(u -> u.get("cpu"))
                        .filter(Objects::nonNull)
                        .mapToDouble(WorkloadOperations::milliCores)
                        .sum();
                usageByPod.put(metrics.getMetadata().getName(), used);
            }
        } catch (Exception e) {
            // metrics-server is optional in a cluster; its absence is a missing
            // signal, not a failure of the control plane.
            log.debug("pod metrics unavailable in namespace {}: {}", namespace, e.getMessage());
            return OptionalDouble.empty();
        }

        List<Double> usages = new ArrayList<>();
        for (Pod pod : pods) {
            Double used = usageByPod.get(pod.getMetadata().getName());
            if (used != null) {
                usages.add(used);
            }
        }
        if (usages.isEmpty()) {
            return OptionalDouble.empty();
        }

        double usedMillis = usages.stream().mapToDouble(Double::doubleValue).sum();
        return OptionalDouble.of((usedMillis / requestedMillis) * 100.0);
    }

    /** Kubernetes CPU quantities are "100m", "0.5" or "2"; all of them mean millicores here. */
    private static double milliCores(Quantity quantity) {
        return Quantity.getAmountInBytes(quantity).doubleValue() * 1000.0;
    }

    private static PodObservation describe(Pod pod) {
        List<ContainerStatus> statuses = pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null
                ? List.of() : pod.getStatus().getContainerStatuses();

        int restarts = statuses.stream()
                .mapToInt(s -> s.getRestartCount() == null ? 0 : s.getRestartCount())
                .sum();

        boolean ready = pod.getStatus() != null && pod.getStatus().getConditions() != null
                && pod.getStatus().getConditions().stream()
                .filter(c -> "Ready".equals(c.getType()))
                .map(PodCondition::getStatus)
                .anyMatch("True"::equals);

        String reason = "";
        for (ContainerStatus status : statuses) {
            if (status.getState() == null) {
                continue;
            }
            if (status.getState().getWaiting() != null && status.getState().getWaiting().getReason() != null) {
                reason = status.getState().getWaiting().getReason();
                break;
            }
            // A running container that was last killed for memory still needs to be
            // reported: the restart already happened, and the cause is the signal.
            if (status.getLastState() != null && status.getLastState().getTerminated() != null
                    && status.getLastState().getTerminated().getReason() != null) {
                reason = status.getLastState().getTerminated().getReason();
                break;
            }
        }
        if (reason.isEmpty() && pod.getStatus() != null && pod.getStatus().getConditions() != null) {
            reason = pod.getStatus().getConditions().stream()
                    .filter(c -> "PodScheduled".equals(c.getType()) && "False".equals(c.getStatus()))
                    .map(PodCondition::getReason)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse("");
        }

        String phase = pod.getStatus() == null || pod.getStatus().getPhase() == null
                ? "Unknown" : pod.getStatus().getPhase();

        return new PodObservation(pod.getMetadata().getName(), phase, ready, restarts, reason);
    }

    /** Sets a workload's replica count. Returns the failure message, or empty on success. */
    public Optional<String> scale(String kubeContext, String namespace, String workload, int replicas) {
        try (KubernetesClient client = clients.clientFor(kubeContext)) {
            Deployment deployment = client.apps().deployments()
                    .inNamespace(namespace).withName(workload).get();
            if (deployment == null) {
                return Optional.of("workload " + namespace + "/" + workload + " not found");
            }
            client.apps().deployments().inNamespace(namespace).withName(workload).scale(replicas);
            log.info("scaled {}/{} to {} replicas", namespace, workload, replicas);
            return Optional.empty();
        } catch (Exception e) {
            return Optional.of(rootMessage(e));
        }
    }

    /**
     * Deletes a pod so its controller replaces it.
     *
     * <p>Deleting rather than "restarting" is deliberate: a Deployment's ReplicaSet
     * recreates the pod, which is the only in-place recovery Kubernetes actually
     * offers, and it gives the scheduler a chance to place the replacement somewhere
     * healthier than where the failing one sat.
     */
    public Optional<String> deletePod(String kubeContext, String namespace, String podName) {
        try (KubernetesClient client = clients.clientFor(kubeContext)) {
            if (client.pods().inNamespace(namespace).withName(podName).get() == null) {
                return Optional.of("pod " + namespace + "/" + podName + " no longer exists");
            }
            client.pods().inNamespace(namespace).withName(podName).delete();
            log.info("deleted pod {}/{} for replacement", namespace, podName);
            return Optional.empty();
        } catch (Exception e) {
            return Optional.of(rootMessage(e));
        }
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
