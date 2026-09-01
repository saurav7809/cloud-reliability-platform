package io.aegiscloud.controlplane.engine;

import io.aegiscloud.controlplane.k8s.KubernetesClientFactory;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Watches clusters for pod failures and reconciles the moment one appears.
 *
 * <p>The scheduled loop alone means a pod can be crash-looping for most of a cycle
 * before the platform looks at it, and that dead time lands directly in the MTTD the
 * platform reports about itself. The cluster already knows; a watch is how it tells
 * you. Kubernetes informers deliver the change in about as long as it takes the API
 * server to write it.
 *
 * <p>The watch never decides anything on its own. It calls the same
 * {@link ReconciliationLoop#reconcileTarget} the timer calls, so an event-driven
 * healing action passes exactly the same policy and autonomy checks as a scheduled
 * one. Making the fast path a shortcut around the guardrails would be the obvious
 * way to turn this from a latency improvement into an incident.
 */
@Service
public class ClusterWatchService {

    private static final Logger log = LoggerFactory.getLogger(ClusterWatchService.class);

    /**
     * The shortest gap between two watch-driven reconciliations of the same target.
     *
     * <p>A crash-looping pod produces a burst of events - waiting, terminated,
     * waiting again - and each one would otherwise start a full reconciliation.
     * Debouncing keeps the response immediate for the first event and stops the rest
     * from turning one failure into a stampede against the API server.
     */
    private final Duration debounce;

    private final ControlPlaneStore store;
    private final ReconciliationLoop loop;
    private final ControlPlaneEvents events;
    private final KubernetesClientFactory clients;
    private final boolean enabled;

    /** One client and one informer per cluster context, held open while watching. */
    private final Map<String, KubernetesClient> watchClients = new ConcurrentHashMap<>();
    private final Map<String, SharedIndexInformer<Pod>> informers = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> lastTriggered = new ConcurrentHashMap<>();

    /**
     * Reconciliation runs off the informer thread.
     *
     * <p>An informer's event thread must return quickly; blocking it on a cluster
     * round-trip and a database write would stall delivery of every subsequent event,
     * including the ones about the pod being fixed.
     */
    private final ExecutorService reconcilers =
            Executors.newFixedThreadPool(2, r -> {
                Thread thread = new Thread(r, "aegis-watch-reconciler");
                thread.setDaemon(true);
                return thread;
            });

    public ClusterWatchService(ControlPlaneStore store, ReconciliationLoop loop,
                               ControlPlaneEvents events, KubernetesClientFactory clients,
                               @Value("${aegiscloud.control-plane.watch-enabled:true}") boolean enabled,
                               @Value("${aegiscloud.control-plane.watch-debounce-seconds:10}")
                               long debounceSeconds) {
        this.store = store;
        this.loop = loop;
        this.events = events;
        this.clients = clients;
        this.enabled = enabled;
        this.debounce = Duration.ofSeconds(debounceSeconds);
    }

    /**
     * Starts watches for any namespace that has targets and is not yet being watched.
     *
     * <p>Runs on a timer rather than once at startup because the set of clusters and
     * namespaces changes while the platform is running: registering a cluster or
     * deploying into a new namespace should start a watch without a restart.
     */
    @Scheduled(initialDelayString = "${aegiscloud.control-plane.watch-start-delay-ms:10000}",
            fixedDelayString = "${aegiscloud.control-plane.watch-refresh-ms:120000}")
    public void refreshWatches() {
        if (!enabled) {
            return;
        }

        Set<String> wanted = new HashSet<>();
        for (ManagedTarget target : store.reachableTargets()) {
            if (target.kubeContext() == null || target.kubeContext().isBlank()) {
                continue;
            }
            String key = target.kubeContext() + "/" + target.namespace();
            wanted.add(key);

            informers.computeIfAbsent(key, k -> startInformer(target.kubeContext(), target.namespace()));
        }

        // A namespace that no longer has targets stops being watched, so a removed
        // cluster does not leave a connection open forever.
        informers.keySet().stream().filter(key -> !wanted.contains(key)).toList()
                .forEach(this::stopInformer);
    }

    private SharedIndexInformer<Pod> startInformer(String kubeContext, String namespace) {
        KubernetesClient client = watchClients.computeIfAbsent(kubeContext, clients::clientFor);

        SharedIndexInformer<Pod> informer = client.pods().inNamespace(namespace)
                .inform(new ResourceEventHandler<>() {
                    @Override
                    public void onAdd(Pod pod) {
                        onPodChanged(kubeContext, namespace, pod);
                    }

                    @Override
                    public void onUpdate(Pod before, Pod after) {
                        onPodChanged(kubeContext, namespace, after);
                    }

                    @Override
                    public void onDelete(Pod pod, boolean finalStateUnknown) {
                        // A deleted pod is usually the platform's own healing action
                        // completing, or a rollout. Reconciling on it would react to
                        // the cure rather than the disease.
                    }
                });

        log.info("watching pods in {}/{}", kubeContext, namespace);
        return informer;
    }

    private void stopInformer(String key) {
        SharedIndexInformer<Pod> informer = informers.remove(key);
        if (informer != null) {
            informer.close();
            log.info("stopped watching {}", key);
        }
    }

    /**
     * Decides whether a pod event is worth waking the control loop for.
     *
     * <p>Most pod updates are noise — a status field refreshed, a condition
     * timestamp moved. Only an unhealthy pod triggers reconciliation, and even then
     * the decision about what to do about it belongs to the engines, not here.
     */
    private void onPodChanged(String kubeContext, String namespace, Pod pod) {
        if (!isUnhealthy(pod)) {
            return;
        }

        String podName = pod.getMetadata().getName();

        store.reachableTargets().stream()
                .filter(t -> kubeContext.equals(t.kubeContext()) && namespace.equals(t.namespace()))
                .filter(t -> podName.startsWith(t.workload() + "-"))
                .findFirst()
                .ifPresent(target -> trigger(target, podName));
    }

    private void trigger(ManagedTarget target, String podName) {
        Instant now = Instant.now();
        Instant last = lastTriggered.get(target.targetId());

        if (last != null && Duration.between(last, now).compareTo(debounce) < 0) {
            return;
        }
        lastTriggered.put(target.targetId(), now);

        events.broadcast("pod-unhealthy", Map.of(
                "at", now.toString(),
                "target", target.label(),
                "pod", podName));

        log.info("watch: {} reported unhealthy pod {}; reconciling now", target.label(), podName);

        reconcilers.submit(() -> {
            try {
                loop.reconcileTarget(target.targetId());
            } catch (Exception e) {
                log.warn("watch-driven reconciliation of {} failed: {}", target.label(), e.getMessage());
            }
        });
    }

    /**
     * Whether the pod is in a state the platform should look at.
     *
     * <p>Deliberately broad: this only decides whether to wake the loop, and the loop
     * re-reads live state before doing anything. A false positive costs one
     * observation; a false negative costs detection time, which is the whole point.
     */
    static boolean isUnhealthy(Pod pod) {
        if (pod.getStatus() == null) {
            return false;
        }
        String phase = pod.getStatus().getPhase();
        if ("Failed".equals(phase) || "Unknown".equals(phase)) {
            return true;
        }

        List<ContainerStatus> statuses = pod.getStatus().getContainerStatuses();
        if (statuses == null) {
            return false;
        }

        return statuses.stream().anyMatch(status -> {
            if (status.getState() == null) {
                return false;
            }
            // A container waiting with a reason is either starting up or stuck;
            // ContainerCreating and PodInitializing are the normal ones and are not
            // worth a reconciliation.
            if (status.getState().getWaiting() != null) {
                String reason = status.getState().getWaiting().getReason();
                return reason != null
                        && !reason.equals("ContainerCreating")
                        && !reason.equals("PodInitializing");
            }
            return status.getState().getTerminated() != null
                    && status.getState().getTerminated().getExitCode() != null
                    && status.getState().getTerminated().getExitCode() != 0;
        });
    }

    /** How many namespaces are currently under watch; reported on the health endpoint. */
    public int watchedNamespaces() {
        return informers.size();
    }

    @PreDestroy
    void shutdown() {
        informers.keySet().stream().toList().forEach(this::stopInformer);
        watchClients.values().forEach(client -> {
            try {
                client.close();
            } catch (Exception e) {
                log.debug("closing watch client failed: {}", e.getMessage());
            }
        });
        watchClients.clear();
        reconcilers.shutdownNow();
    }
}
