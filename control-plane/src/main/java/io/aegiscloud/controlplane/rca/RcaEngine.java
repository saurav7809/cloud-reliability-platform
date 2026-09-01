package io.aegiscloud.controlplane.rca;

import io.aegiscloud.controlplane.graph.ServiceGraph;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Root cause analysis: rank the degraded services by how well each explains the rest.
 *
 * <p>Pure. No database, no cluster, no clock of its own — every input is passed in,
 * which is what allows accuracy to be measured against the chaos experiments of
 * Phase 6, where the true cause is known because the platform caused it.
 *
 * <p>Two rules shape everything here.
 *
 * <p><b>A verdict must cite its evidence (FR-29).</b> Confidence is derived from the
 * evidence list rather than computed alongside it, so a candidate with no supporting
 * facts scores nothing and is dropped. There is no path to an unexplainable verdict
 * because there is no code that produces confidence any other way.
 *
 * <p><b>The engine reads and never writes.</b> Nothing here touches a cluster. A wrong
 * diagnosis stays a wrong sentence on a screen; only the policy-gated Control Plane
 * acts, and that separation is what stops a misdiagnosis from becoming an unbounded
 * action.
 */
public final class RcaEngine {

    /**
     * Weights per signal class.
     *
     * <p>Graph position leads because it is the only signal that distinguishes cause
     * from symptom structurally: a service downstream of the others cannot be the
     * origin of their failure, whatever else is true of it. Temporal order is close
     * behind and would lead if clocks were trustworthy at second granularity across
     * a fleet, which they are not.
     */
    static final double GRAPH_WEIGHT = 1.0;
    static final double TEMPORAL_WEIGHT = 0.8;
    static final double CHANGE_WEIGHT = 0.7;
    static final double SATURATION_WEIGHT = 0.5;

    /** At or above this, a candidate is presented as a likely cause rather than a possibility. */
    static final double LIKELY_CAUSE_CONFIDENCE = 0.45;

    private RcaEngine() {
    }

    /**
     * Everything known about one degraded service at the time of the incident.
     *
     * @param degradedAt         when this service was first observed degraded, absent
     *                           when no such observation exists
     * @param changeEvents       deployments, scaling actions and healing events within
     *                           the incident window, most recent first
     * @param unhealthyPodCount  pods in a failing state right now
     * @param scoreDrop          how far its reliability score fell
     */
    public record CandidateInput(
            String serviceId,
            String serviceName,
            String targetId,
            Optional<Instant> degradedAt,
            List<String> changeEvents,
            int unhealthyPodCount,
            double scoreDrop) {
    }

    /**
     * What the engine is claiming about a candidate.
     *
     * <p>A pure symptom is kept in the list rather than dropped, and labelled. In the
     * incident this whole layer exists for - three services alerting, one actually
     * broken - saying "these two are downstream of the first and need no separate
     * investigation" is as valuable as naming the cause, and it is the part that
     * stops three teams being paged. Dropping low-confidence candidates would throw
     * exactly that away.
     */
    public enum Assessment {
        LIKELY_CAUSE,
        POSSIBLE_CAUSE,
        /** Downstream of another failure, or too late to have started it. */
        LIKELY_SYMPTOM
    }

    /** A ranked candidate with the facts it rests on. */
    public record Verdict(
            String serviceId,
            String serviceName,
            String targetId,
            int rank,
            double confidence,
            Assessment assessment,
            String reasoning,
            List<Evidence> evidence,
            Map<Evidence.Signal, Double> signalScores) {
    }

    /**
     * Ranks candidates for one incident.
     *
     * @param graph     the dependency graph, used for structural reasoning
     * @param incidentStart when the incident began, for judging how close a change was
     */
    public static List<Verdict> analyse(ServiceGraph graph, List<CandidateInput> candidates,
                                        Instant incidentStart) {
        if (candidates.isEmpty()) {
            return List.of();
        }

        Set<String> degradedIds = candidates.stream()
                .map(CandidateInput::serviceId)
                .collect(java.util.stream.Collectors.toSet());

        Optional<Instant> earliest = candidates.stream()
                .map(CandidateInput::degradedAt)
                .flatMap(Optional::stream)
                .min(Comparator.naturalOrder());

        List<Verdict> verdicts = new ArrayList<>();

        for (CandidateInput candidate : candidates) {
            List<Evidence> evidence = new ArrayList<>();

            evidence.addAll(graphEvidence(graph, candidate, degradedIds));
            evidence.addAll(temporalEvidence(candidate, earliest));
            evidence.addAll(changeEvidence(candidate));
            evidence.addAll(saturationEvidence(candidate));

            // FR-29 enforced structurally: no evidence, no verdict. Not a filter
            // applied afterwards - there is simply no confidence to compute.
            if (evidence.isEmpty()) {
                continue;
            }

            Map<Evidence.Signal, Double> signalScores = scoreBySignal(evidence);
            double confidence = confidenceFrom(signalScores);
            Assessment assessment = assess(confidence, evidence);

            verdicts.add(new Verdict(candidate.serviceId(), candidate.serviceName(),
                    candidate.targetId(), 0, confidence, assessment,
                    reasoning(candidate, evidence, assessment), evidence, signalScores));
        }

        verdicts.sort(Comparator.comparingDouble(Verdict::confidence).reversed()
                .thenComparing(Verdict::serviceName));

        List<Verdict> ranked = new ArrayList<>();
        for (int i = 0; i < verdicts.size(); i++) {
            Verdict v = verdicts.get(i);
            ranked.add(new Verdict(v.serviceId(), v.serviceName(), v.targetId(), i + 1,
                    v.confidence(), v.assessment(), v.reasoning(), v.evidence(), v.signalScores()));
        }
        return ranked;
    }

    /**
     * Structural evidence: does this candidate sit upstream of the other failures?
     *
     * <p>This is the signal that separates cause from symptom. A service downstream of
     * every other degraded service is almost certainly a victim, and saying so
     * explicitly is as useful as naming the cause — it is what stops three teams
     * being paged for one fault.
     */
    private static List<Evidence> graphEvidence(ServiceGraph graph, CandidateInput candidate,
                                                Set<String> degraded) {
        if (!graph.contains(candidate.serviceId())) {
            return List.of();
        }

        Set<String> downstream = graph.blastRadius(candidate.serviceId()).affected();
        Set<String> upstream = graph.dependencies(candidate.serviceId());

        List<Evidence> evidence = new ArrayList<>();

        long explains = degraded.stream()
                .filter(id -> !id.equals(candidate.serviceId()))
                .filter(downstream::contains)
                .count();

        long explainedBy = degraded.stream()
                .filter(id -> !id.equals(candidate.serviceId()))
                .filter(upstream::contains)
                .count();

        if (explains > 0) {
            // Scaled by how much of the incident it accounts for, so a service
            // upstream of one other failure does not look like one upstream of six.
            double share = (double) explains / Math.max(1, degraded.size() - 1);
            evidence.add(Evidence.of(Evidence.Signal.GRAPH_POSITION, share,
                    candidate.serviceName() + " is upstream of " + explains + " of the "
                            + (degraded.size() - 1) + " other degraded service(s)"));
        }

        if (explainedBy > 0) {
            // Being downstream of another failure argues against this candidate, and
            // the argument is recorded rather than silently subtracted.
            evidence.add(Evidence.of(Evidence.Signal.GRAPH_POSITION, -0.6,
                    candidate.serviceName() + " is downstream of " + explainedBy
                            + " other degraded service(s), so its failure may be a symptom"));
        }

        return evidence;
    }

    /**
     * Temporal evidence: what degraded first.
     *
     * <p>A symptom cannot precede its cause. The window is generous because clock
     * skew across a fleet and probe intervals both blur the ordering — treating a
     * two-second difference as decisive would be reading precision that is not there.
     */
    private static List<Evidence> temporalEvidence(CandidateInput candidate,
                                                   Optional<Instant> earliest) {
        if (candidate.degradedAt().isEmpty() || earliest.isEmpty()) {
            return List.of();
        }

        Duration after = Duration.between(earliest.get(), candidate.degradedAt().get());

        if (after.isZero() || after.abs().toSeconds() <= 5) {
            return List.of(Evidence.of(Evidence.Signal.TEMPORAL_ORDER, 1.0,
                    candidate.serviceName() + " degraded first, at "
                            + candidate.degradedAt().get()));
        }

        if (after.toSeconds() > 60) {
            return List.of(Evidence.of(Evidence.Signal.TEMPORAL_ORDER, -0.4,
                    candidate.serviceName() + " degraded " + after.toSeconds()
                            + "s after the first failure, which is late for a cause"));
        }

        // Within a minute of the first failure: consistent with being the cause, but
        // not evidence for it over the others.
        return List.of(Evidence.of(Evidence.Signal.TEMPORAL_ORDER, 0.3,
                candidate.serviceName() + " degraded " + after.toSeconds()
                        + "s after the first failure, close enough to be part of the same event"));
    }

    /**
     * Change evidence: something was done to this service near the incident.
     *
     * <p>The oldest heuristic in operations, and still among the best — most
     * incidents follow a change. It is weighted below graph position because it is
     * also the easiest to be fooled by: the platform's own healing action on a
     * suffering service is a change event, and it is a response to the incident
     * rather than its cause.
     */
    private static List<Evidence> changeEvidence(CandidateInput candidate) {
        if (candidate.changeEvents().isEmpty()) {
            return List.of();
        }

        return List.of(Evidence.of(Evidence.Signal.CHANGE_EVENT,
                Math.min(1.0, candidate.changeEvents().size() / 2.0),
                candidate.changeEvents().size() + " change event(s) near the incident: "
                        + String.join("; ", candidate.changeEvents())));
    }

    /** Resource evidence: the pods themselves are in trouble. */
    private static List<Evidence> saturationEvidence(CandidateInput candidate) {
        List<Evidence> evidence = new ArrayList<>();

        if (candidate.unhealthyPodCount() > 0) {
            evidence.add(Evidence.of(Evidence.Signal.RESOURCE_SATURATION,
                    Math.min(1.0, candidate.unhealthyPodCount() / 2.0),
                    candidate.unhealthyPodCount() + " pod(s) unhealthy on this service"));
        }

        if (candidate.scoreDrop() >= 20) {
            evidence.add(Evidence.of(Evidence.Signal.RESOURCE_SATURATION,
                    Math.min(1.0, candidate.scoreDrop() / 100.0),
                    String.format("reliability score fell %.1f points", candidate.scoreDrop())));
        }

        return evidence;
    }

    private static Map<Evidence.Signal, Double> scoreBySignal(List<Evidence> evidence) {
        Map<Evidence.Signal, Double> scores = new EnumMap<>(Evidence.Signal.class);
        for (Evidence item : evidence) {
            scores.merge(item.signal(), item.weight(), Double::sum);
        }
        return scores;
    }

    /**
     * Confidence from the weighted signals, squashed into 0..1.
     *
     * <p>Normalised by the total possible weight rather than by the weight observed,
     * so a candidate supported by one signal cannot reach the same confidence as one
     * supported by four. That is the whole point of correlating across signal classes:
     * agreement between independent signals is what makes a verdict worth trusting.
     */
    static double confidenceFrom(Map<Evidence.Signal, Double> signalScores) {
        double weighted =
                clamp(signalScores.getOrDefault(Evidence.Signal.GRAPH_POSITION, 0.0)) * GRAPH_WEIGHT
                        + clamp(signalScores.getOrDefault(Evidence.Signal.TEMPORAL_ORDER, 0.0)) * TEMPORAL_WEIGHT
                        + clamp(signalScores.getOrDefault(Evidence.Signal.CHANGE_EVENT, 0.0)) * CHANGE_WEIGHT
                        + clamp(signalScores.getOrDefault(Evidence.Signal.RESOURCE_SATURATION, 0.0)) * SATURATION_WEIGHT;

        double maximum = GRAPH_WEIGHT + TEMPORAL_WEIGHT + CHANGE_WEIGHT + SATURATION_WEIGHT;
        return Math.max(0, Math.min(1, weighted / maximum));
    }

    /** Individual signals are bounded so one loud signal cannot dominate the rest. */
    private static double clamp(double value) {
        return Math.max(-1, Math.min(1, value));
    }

    /**
     * Whether this candidate is being offered as a cause or explained away as a
     * symptom.
     *
     * <p>Negative evidence decides it, not the confidence number alone: a service the
     * graph places downstream of another failure is a symptom even if it happens to
     * have a dramatic score drop, and that is precisely the case where a confidence
     * threshold on its own would mislead.
     */
    static Assessment assess(double confidence, List<Evidence> evidence) {
        boolean arguedAgainst = evidence.stream()
                .anyMatch(e -> e.weight() < 0 && e.signal() == Evidence.Signal.GRAPH_POSITION);

        if (arguedAgainst) {
            return Assessment.LIKELY_SYMPTOM;
        }
        return confidence >= LIKELY_CAUSE_CONFIDENCE
                ? Assessment.LIKELY_CAUSE : Assessment.POSSIBLE_CAUSE;
    }

    private static String reasoning(CandidateInput candidate, List<Evidence> evidence,
                                    Assessment assessment) {
        List<String> supporting = evidence.stream()
                .filter(e -> e.weight() > 0)
                .map(Evidence::description)
                .toList();
        List<String> against = evidence.stream()
                .filter(e -> e.weight() < 0)
                .map(Evidence::description)
                .toList();

        StringBuilder reasoning = new StringBuilder();
        reasoning.append(supporting.isEmpty()
                ? "No supporting evidence."
                : String.join(". ", supporting) + ".");

        if (!against.isEmpty()) {
            // Stating the counter-evidence is what makes the confidence readable
            // instead of merely numeric.
            reasoning.append(" Against: ").append(String.join("; ", against)).append(".");
        }

        if (assessment == Assessment.LIKELY_SYMPTOM) {
            reasoning.append(" Assessed as a symptom rather than a cause; "
                    + "it needs no separate investigation unless the cause is ruled out.");
        }
        return reasoning.toString();
    }

    /**
     * Accuracy against a known cause — the measurement FR-29's target rests on.
     *
     * @param correct  incidents where the top-ranked verdict named the true cause
     * @param total    incidents with a known cause
     */
    public record Accuracy(int correct, int total, double precisionAt1, List<String> detail) {

        public static Accuracy of(List<String> detail, int correct, int total) {
            return new Accuracy(correct, total, total == 0 ? 0 : (double) correct / total, detail);
        }
    }

    /** Convenience for the accuracy harness: was the true cause ranked first? */
    public static boolean topRankedIs(List<Verdict> verdicts, String expectedServiceId) {
        return !verdicts.isEmpty() && verdicts.get(0).serviceId().equals(expectedServiceId);
    }

    /** Where the true cause appeared in the ranking, or empty when it did not appear. */
    public static Optional<Integer> rankOf(List<Verdict> verdicts, String serviceId) {
        return verdicts.stream()
                .filter(v -> v.serviceId().equals(serviceId))
                .map(Verdict::rank)
                .findFirst();
    }

    /** The verdicts rendered for the API, with signal names as plain strings. */
    public static Map<String, Object> asPayload(Verdict verdict) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rank", verdict.rank());
        payload.put("service", verdict.serviceName());
        payload.put("confidence", Math.round(verdict.confidence() * 1000) / 1000.0);
        payload.put("assessment", verdict.assessment().name());
        payload.put("reasoning", verdict.reasoning());
        payload.put("evidence", verdict.evidence().stream()
                .map(e -> Map.of("signal", e.signal().name(), "weight", e.weight(),
                        "detail", e.description()))
                .toList());
        return payload;
    }
}
