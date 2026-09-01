package io.aegiscloud.controlplane.rca;

import io.aegiscloud.controlplane.engine.ControlPlaneEvents;
import io.aegiscloud.controlplane.graph.GraphStore;
import io.aegiscloud.controlplane.graph.ServiceGraph;
import io.aegiscloud.controlplane.k8s.WorkloadOperations;
import io.aegiscloud.controlplane.k8s.WorkloadOperations.PodObservation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns the platform's observations into a diagnosis.
 *
 * <p>Gathers the four signal classes, hands them to the pure {@link RcaEngine}, and
 * records what came back. The engine does the reasoning; this class does the fetching
 * and the writing, which keeps the part that has to be correct free of anything that
 * needs a database to test.
 *
 * <p><b>It never writes to a cluster.</b> Every cluster call below is a read. That is
 * an architectural constraint rather than an implementation detail: the Intelligence
 * Layer diagnoses and the policy-gated Control Plane acts, and keeping them apart is
 * what stops a wrong diagnosis from becoming an unbounded action.
 */
@Service
public class DiagnosisService {

    private static final Logger log = LoggerFactory.getLogger(DiagnosisService.class);

    private final RcaStore store;
    private final GraphStore graphs;
    private final WorkloadOperations workloads;
    private final ControlPlaneEvents events;
    private final double degradedBelow;
    private final Duration lookback;

    public DiagnosisService(RcaStore store, GraphStore graphs, WorkloadOperations workloads,
                            ControlPlaneEvents events,
                            @Value("${aegiscloud.rca.degraded-below:80}") double degradedBelow,
                            @Value("${aegiscloud.rca.lookback-minutes:30}") long lookbackMinutes) {
        this.store = store;
        this.graphs = graphs;
        this.workloads = workloads;
        this.events = events;
        this.degradedBelow = degradedBelow;
        this.lookback = Duration.ofMinutes(lookbackMinutes);
    }

    /** A diagnosis and the incident it belongs to. */
    public record Diagnosis(String incidentId, String title, int degradedServices,
                            List<Map<String, Object>> verdicts, String summary) {
    }

    /**
     * Diagnoses whatever is degraded right now.
     *
     * @return empty when nothing is degraded — an incident with no failures is not an
     *         incident, and opening one would fill the history with noise
     */
    public Optional<Diagnosis> diagnoseCurrent() {
        List<RcaStore.DegradedTarget> degraded = store.degradedTargets(degradedBelow);
        if (degraded.isEmpty()) {
            return Optional.empty();
        }

        Instant now = Instant.now();
        Instant windowStart = now.minus(lookback);

        String title = degraded.size() == 1
                ? degraded.get(0).serviceName() + " degraded"
                : degraded.size() + " services degraded";

        UUID incidentId = store.open(title, degraded.size());

        return Optional.of(diagnose(incidentId, title, degraded, windowStart, now));
    }

    /**
     * Diagnoses a specific window, which is how a past chaos run is re-analysed.
     *
     * <p>Same code path as a live diagnosis, deliberately: an accuracy measurement
     * taken against a different implementation from the one that runs in anger would
     * measure the wrong thing.
     */
    public List<RcaEngine.Verdict> analyseWindow(Instant from, Instant to) {
        List<RcaStore.DegradedTarget> degraded = store.targetsDegradedDuring(from, to, degradedBelow);
        if (degraded.isEmpty()) {
            return List.of();
        }
        return RcaEngine.analyse(graphs.load(), gather(degraded, from, to), from);
    }

    private Diagnosis diagnose(UUID incidentId, String title, List<RcaStore.DegradedTarget> degraded,
                               Instant windowStart, Instant now) {

        ServiceGraph graph = graphs.load();
        List<RcaEngine.CandidateInput> candidates = gather(degraded, windowStart, now);
        List<RcaEngine.Verdict> verdicts = RcaEngine.analyse(graph, candidates, windowStart);

        store.saveVerdicts(incidentId, verdicts);

        String summary;
        if (verdicts.isEmpty()) {
            // FR-29 in practice: no evidence, no verdict, and the incident says so
            // rather than offering a candidate it cannot justify.
            summary = "no candidate could be supported by evidence; "
                    + "the incident is recorded without a verdict";
        } else {
            RcaEngine.Verdict top = verdicts.get(0);
            store.recordRootCause(incidentId, UUID.fromString(top.targetId()), top.confidence());
            summary = String.format("%s, %s (confidence %.2f)", top.serviceName(),
                    top.assessment().name().toLowerCase().replace('_', ' '), top.confidence());
        }

        log.info("incident {}: {} - {}", incidentId, title, summary);
        events.broadcast("incident", Map.of(
                "incidentId", incidentId.toString(),
                "title", title,
                "degraded", degraded.size(),
                "summary", summary));

        return new Diagnosis(incidentId.toString(), title, degraded.size(),
                verdicts.stream().map(RcaEngine::asPayload).toList(), summary);
    }

    /** Collects the four signal classes for every degraded target. */
    private List<RcaEngine.CandidateInput> gather(List<RcaStore.DegradedTarget> degraded,
                                                  Instant windowStart, Instant now) {
        List<RcaEngine.CandidateInput> candidates = new ArrayList<>();

        for (RcaStore.DegradedTarget target : degraded) {
            Optional<Instant> degradedAt =
                    store.degradedSince(target.targetId(), degradedBelow, windowStart);

            List<String> changes = store.changeEvents(target.targetId(), windowStart);

            // A live read of the pods. This is the only cluster call in the whole
            // Intelligence Layer, and it is a list operation.
            int unhealthyPods = (int) workloads
                    .observe(target.kubeContext(), target.namespace(), target.workload())
                    .pods().stream()
                    .filter(pod -> !pod.ready())
                    .count();

            double previousScore = store.scoreBefore(target.targetId(), windowStart).orElse(100.0);
            double drop = Math.max(0, previousScore - target.score());

            candidates.add(new RcaEngine.CandidateInput(
                    target.serviceId().toString(), target.serviceName(),
                    target.targetId().toString(), degradedAt, changes, unhealthyPods, drop));
        }

        return candidates;
    }

    /**
     * Measures RCA against the chaos runs, where the true cause is known (FR-29's
     * stated bar is 70% precision@1).
     *
     * <p>Only runs that actually degraded something are counted. A chaos experiment
     * the system shrugged off has no incident to diagnose, and scoring RCA on it
     * would inflate or deflate the number depending on which way the empty case was
     * counted — neither of which measures anything.
     */
    public RcaEngine.Accuracy measureAccuracy(int limit) {
        List<String> detail = new ArrayList<>();
        int correct = 0;
        int scored = 0;

        for (RcaStore.ChaosGroundTruth truth : store.chaosGroundTruth(limit)) {
            List<RcaEngine.Verdict> verdicts = analyseWindow(truth.startedAt(), truth.endedAt());

            if (verdicts.isEmpty()) {
                detail.add(String.format("%s (%s): nothing degraded measurably; not scored",
                        truth.faultServiceName(), truth.faultType()));
                continue;
            }

            scored++;
            String expected = truth.faultServiceId().toString();
            boolean topIsCorrect = RcaEngine.topRankedIs(verdicts, expected);

            if (topIsCorrect) {
                correct++;
            }

            detail.add(String.format("%s (%s): top verdict was %s%s",
                    truth.faultServiceName(), truth.faultType(), verdicts.get(0).serviceName(),
                    topIsCorrect ? " - correct"
                            : " - wrong; true cause ranked "
                            + RcaEngine.rankOf(verdicts, expected)
                            .map(String::valueOf).orElse("nowhere")));
        }

        return RcaEngine.Accuracy.of(detail, correct, scored);
    }
}
