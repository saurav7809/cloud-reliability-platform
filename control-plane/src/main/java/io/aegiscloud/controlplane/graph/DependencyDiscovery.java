package io.aegiscloud.controlplane.graph;

import io.aegiscloud.controlplane.engine.ControlPlaneEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Dependency discovery from chaos experiments.
 *
 * <p>The architecture's intended source for the graph is OpenTelemetry spans, and
 * that remains the right answer once tracing is deployed. This is a different and in
 * one respect stronger signal: a trace shows that A called B, while a
 * DEPENDENCY_OUTAGE experiment shows that A stops working when B is taken away. Those
 * are not the same claim, and the second is the one a blast radius actually asserts —
 * a service can call another on a path nobody depends on, and the trace cannot tell
 * you which.
 *
 * <p>What it cannot do is find dependencies nobody has experimented on. Discovery
 * here is therefore deliberately narrow: it records only what an experiment
 * demonstrated, never what one might imply.
 */
@Service
public class DependencyDiscovery {

    private static final Logger log = LoggerFactory.getLogger(DependencyDiscovery.class);

    /**
     * How far a service's score must fall during a dependency outage before the
     * platform will claim it depends on the service that was taken down.
     *
     * <p>Set well above measurement noise. A one-point wobble is not evidence, and a
     * wrong edge here is worse than a missing one: it produces a blast radius that
     * looks authoritative and is not.
     */
    static final double DEGRADATION_THRESHOLD = 10.0;

    private final GraphStore store;
    private final ControlPlaneEvents events;

    public DependencyDiscovery(GraphStore store, ControlPlaneEvents events) {
        this.store = store;
        this.events = events;
    }

    /**
     * One service's reliability before and during an outage of the service under test.
     */
    public record ObservedImpact(UUID serviceId, String serviceName,
                                 double scoreBefore, double scoreDuring) {

        double drop() {
            return scoreBefore - scoreDuring;
        }
    }

    /** What the experiment concluded about the graph. */
    public record DiscoveryResult(int edgesRecorded, List<String> findings) {
    }

    /**
     * Records dependency edges demonstrated by taking one service down.
     *
     * @param outageServiceId the service that was scaled to zero
     * @param observed        every other service's score before and during the outage
     */
    public DiscoveryResult recordFromOutage(UUID outageServiceId, List<ObservedImpact> observed) {
        String outageName = store.serviceName(outageServiceId).orElse(outageServiceId.toString());
        List<String> findings = new java.util.ArrayList<>();
        int recorded = 0;

        for (ObservedImpact impact : observed) {
            if (impact.serviceId().equals(outageServiceId)) {
                continue;
            }

            if (impact.drop() < DEGRADATION_THRESHOLD) {
                findings.add(String.format("%s held up (%.1f -> %.1f); no dependency recorded",
                        impact.serviceName(), impact.scoreBefore(), impact.scoreDuring()));
                continue;
            }

            store.upsertEdge(impact.serviceId(), outageServiceId, "EXPERIMENT",
                    0, 0, 0);
            recorded++;

            String finding = String.format(
                    "%s degraded %.1f -> %.1f while %s was down; recorded %s -> %s",
                    impact.serviceName(), impact.scoreBefore(), impact.scoreDuring(),
                    outageName, impact.serviceName(), outageName);
            findings.add(finding);
            log.info("dependency discovered by experiment: {}", finding);
        }

        if (recorded > 0) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("outageService", outageName);
            payload.put("edgesRecorded", recorded);
            events.broadcast("dependency-discovered", payload);
        }

        return new DiscoveryResult(recorded, findings);
    }

    /** The service behind a deployment target, for callers holding a target id. */
    public Optional<UUID> serviceOf(UUID targetId) {
        return store.serviceOfTarget(targetId);
    }
}
