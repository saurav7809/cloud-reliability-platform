package io.aegiscloud.controlplane.rca;

import io.aegiscloud.controlplane.graph.ServiceGraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Root cause analysis against the scenario the Intelligence Layer exists for: three
 * services alert at once and only one is actually broken.
 */
class RcaEngineTest {

    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");

    /** web -> api -> auth. Failure in auth surfaces as three alerts. */
    private static ServiceGraph graph() {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("web", "web");
        names.put("api", "api");
        names.put("auth", "auth");

        return new ServiceGraph(names, List.of(
                new ServiceGraph.Edge("web", "api", "MANUAL", 100, 0, 30),
                new ServiceGraph.Edge("api", "auth", "MANUAL", 100, 0, 30)));
    }

    private static RcaEngine.CandidateInput candidate(String id, Instant degradedAt,
                                                      List<String> changes, int unhealthyPods,
                                                      double scoreDrop) {
        return new RcaEngine.CandidateInput(id, id, "target-" + id,
                Optional.ofNullable(degradedAt), changes, unhealthyPods, scoreDrop);
    }

    @Test
    @DisplayName("the upstream service that degraded first is ranked above its symptoms")
    void namesTheCauseNotTheSymptoms() {
        List<RcaEngine.Verdict> verdicts = RcaEngine.analyse(graph(), List.of(
                candidate("auth", T0, List.of(), 2, 60),
                candidate("api", T0.plusSeconds(20), List.of(), 0, 35),
                candidate("web", T0.plusSeconds(30), List.of(), 0, 30)), T0);

        assertThat(verdicts.get(0).serviceName()).isEqualTo("auth");
        assertThat(verdicts.get(0).rank()).isEqualTo(1);
        assertThat(RcaEngine.topRankedIs(verdicts, "auth")).isTrue();
    }

    @Test
    @DisplayName("a downstream symptom is told it may be a symptom, in the verdict text")
    void symptomsAreLabelledAsSuch() {
        List<RcaEngine.Verdict> verdicts = RcaEngine.analyse(graph(), List.of(
                candidate("auth", T0, List.of(), 2, 60),
                candidate("web", T0.plusSeconds(30), List.of(), 0, 30)), T0);

        RcaEngine.Verdict web = verdicts.stream()
                .filter(v -> v.serviceName().equals("web")).findFirst().orElseThrow();

        assertThat(web.reasoning()).contains("may be a symptom");
        assertThat(web.assessment()).isEqualTo(RcaEngine.Assessment.LIKELY_SYMPTOM);
        assertThat(web.confidence()).isLessThan(verdicts.get(0).confidence());
        // Kept in the list, not dropped: telling the operator these alerts need no
        // separate investigation is the point of the exercise.
        assertThat(verdicts).hasSize(2);
    }

    @Test
    @DisplayName("every verdict cites evidence; a candidate with none is not shown at all")
    void noVerdictWithoutEvidence() {
        // A service with no graph position, no timing, no changes and healthy pods.
        Map<String, String> names = new LinkedHashMap<>(Map.of("lonely", "lonely"));
        ServiceGraph isolated = new ServiceGraph(names, List.of());

        List<RcaEngine.Verdict> verdicts = RcaEngine.analyse(isolated,
                List.of(candidate("lonely", null, List.of(), 0, 2)), T0);

        assertThat(verdicts).isEmpty();
    }

    @Test
    @DisplayName("all shown verdicts carry at least one piece of evidence")
    void allVerdictsAreExplainable() {
        List<RcaEngine.Verdict> verdicts = RcaEngine.analyse(graph(), List.of(
                candidate("auth", T0, List.of("deployed v2"), 1, 40),
                candidate("api", T0.plusSeconds(10), List.of(), 0, 25),
                candidate("web", T0.plusSeconds(15), List.of(), 0, 22)), T0);

        assertThat(verdicts).isNotEmpty();
        assertThat(verdicts).allSatisfy(v -> {
            assertThat(v.evidence()).isNotEmpty();
            assertThat(v.reasoning()).isNotBlank();
        });
    }

    @Test
    @DisplayName("a recent change lifts a candidate that the graph alone would not distinguish")
    void changeEventsCarryWeight() {
        Map<String, String> names = new LinkedHashMap<>(Map.of("a", "a", "b", "b"));
        ServiceGraph flat = new ServiceGraph(names, List.of());

        List<RcaEngine.Verdict> verdicts = RcaEngine.analyse(flat, List.of(
                candidate("a", T0, List.of("deployed 2 minutes ago"), 1, 40),
                candidate("b", T0, List.of(), 1, 40)), T0);

        assertThat(verdicts.get(0).serviceName()).isEqualTo("a");
        assertThat(verdicts.get(0).evidence())
                .anyMatch(e -> e.signal() == Evidence.Signal.CHANGE_EVENT);
    }

    @Test
    @DisplayName("degrading long after everything else counts against a candidate")
    void lateDegradationArguesAgainst() {
        List<RcaEngine.Verdict> verdicts = RcaEngine.analyse(graph(), List.of(
                candidate("auth", T0, List.of(), 1, 40),
                candidate("web", T0.plusSeconds(600), List.of(), 1, 40)), T0);

        RcaEngine.Verdict web = verdicts.stream()
                .filter(v -> v.serviceName().equals("web")).findFirst().orElseThrow();

        assertThat(web.evidence()).anyMatch(e ->
                e.signal() == Evidence.Signal.TEMPORAL_ORDER && e.weight() < 0);
    }

    @Test
    @DisplayName("agreement across signals outranks one loud signal")
    void correlationBeatsASingleShoutingSignal() {
        Map<String, String> names = new LinkedHashMap<>(Map.of("quiet", "quiet", "loud", "loud"));
        ServiceGraph flat = new ServiceGraph(names, List.of(
                new ServiceGraph.Edge("loud", "quiet", "MANUAL", 10, 0, 10)));

        // quiet: upstream, first to degrade, one change, one bad pod - four signals.
        // loud: nothing but a spectacular score drop.
        List<RcaEngine.Verdict> verdicts = RcaEngine.analyse(flat, List.of(
                candidate("quiet", T0, List.of("config changed"), 1, 25),
                candidate("loud", T0.plusSeconds(120), List.of(), 9, 95)), T0);

        assertThat(verdicts.get(0).serviceName()).isEqualTo("quiet");
        assertThat(verdicts.get(0).signalScores()).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("a single degraded service is still diagnosed, with the evidence it has")
    void singleServiceIncident() {
        List<RcaEngine.Verdict> verdicts = RcaEngine.analyse(graph(),
                List.of(candidate("auth", T0, List.of("scaled down 1 minute ago"), 3, 55)), T0);

        assertThat(verdicts).hasSize(1);
        assertThat(verdicts.get(0).serviceName()).isEqualTo("auth");
        assertThat(verdicts.get(0).evidence()).isNotEmpty();
    }

    @Test
    @DisplayName("an empty incident produces no verdicts rather than an empty guess")
    void noCandidatesNoVerdicts() {
        assertThat(RcaEngine.analyse(graph(), List.of(), T0)).isEmpty();
    }

    @Test
    @DisplayName("confidence stays inside 0..1 however extreme the inputs")
    void confidenceIsBounded() {
        List<RcaEngine.Verdict> verdicts = RcaEngine.analyse(graph(), List.of(
                candidate("auth", T0, List.of("a", "b", "c", "d", "e", "f"), 99, 100)), T0);

        assertThat(verdicts.get(0).confidence()).isBetween(0.0, 1.0);
    }

    @Test
    @DisplayName("ranks are dense and start at one, so the list reads as a ranking")
    void ranksAreWellFormed() {
        List<RcaEngine.Verdict> verdicts = RcaEngine.analyse(graph(), List.of(
                candidate("auth", T0, List.of(), 2, 60),
                candidate("api", T0.plusSeconds(10), List.of(), 0, 35),
                candidate("web", T0.plusSeconds(20), List.of(), 0, 30)), T0);

        for (int i = 0; i < verdicts.size(); i++) {
            assertThat(verdicts.get(i).rank()).isEqualTo(i + 1);
        }
    }

    @Test
    @DisplayName("the precision@1 target is met on the incident shape the platform is built for")
    void meetsPrecisionTargetOnKnownCauses() {
        // Ten incidents whose true cause is known, of the kind a DEPENDENCY_OUTAGE
        // experiment produces: one service broken, its callers degraded after it.
        int correct = 0;
        for (int i = 0; i < 10; i++) {
            List<RcaEngine.Verdict> verdicts = RcaEngine.analyse(graph(), List.of(
                    candidate("auth", T0, List.of("experiment: dependency outage"), 1 + i % 3, 40 + i),
                    candidate("api", T0.plusSeconds(10 + i), List.of(), 0, 20 + i),
                    candidate("web", T0.plusSeconds(20 + i), List.of(), 0, 15 + i)), T0);

            if (RcaEngine.topRankedIs(verdicts, "auth")) {
                correct++;
            }
        }

        // FR-29's stated bar is 70% precision@1.
        assertThat(correct / 10.0).isGreaterThanOrEqualTo(0.7);
    }
}
