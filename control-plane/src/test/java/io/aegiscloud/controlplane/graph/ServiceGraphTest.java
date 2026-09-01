package io.aegiscloud.controlplane.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The graph algorithms, against topologies drawn by hand.
 *
 * <p>Includes the cases that are rare in a demo and routine in production: cycles,
 * diamonds with a redundant path, disconnected components, and a service nothing
 * calls. A blast radius that is wrong in one of these is wrong exactly when someone
 * is relying on it during an incident.
 */
class ServiceGraphTest {

    /** web -> api -> {auth, catalog} -> db. A shape most systems have somewhere. */
    private static ServiceGraph standardGraph() {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("web", "web");
        names.put("api", "api");
        names.put("auth", "auth");
        names.put("catalog", "catalog");
        names.put("db", "db");

        return new ServiceGraph(names, List.of(
                edge("web", "api"),
                edge("api", "auth"),
                edge("api", "catalog"),
                edge("auth", "db"),
                edge("catalog", "db")));
    }

    private static ServiceGraph.Edge edge(String caller, String callee) {
        return new ServiceGraph.Edge(caller, callee, "MANUAL", 10, 0, 20);
    }

    @Test
    @DisplayName("blast radius walks backwards: when the database fails, everything above it suffers")
    void blastRadiusFollowsCallersNotCallees() {
        ServiceGraph.BlastRadius radius = standardGraph().blastRadius("db");

        assertThat(radius.affected()).containsExactlyInAnyOrder("auth", "catalog", "api", "web");
        assertThat(radius.depth()).containsEntry("auth", 1);
        assertThat(radius.depth()).containsEntry("api", 2);
        assertThat(radius.depth()).containsEntry("web", 3);
    }

    @Test
    @DisplayName("a leaf service takes nothing down with it")
    void entryPointHasEmptyBlastRadius() {
        assertThat(standardGraph().blastRadius("web").affected()).isEmpty();
    }

    @Test
    @DisplayName("dependencies walk forwards: what a service needs in order to work")
    void dependenciesFollowCallees() {
        assertThat(standardGraph().dependencies("web"))
                .containsExactlyInAnyOrder("api", "auth", "catalog", "db");
        assertThat(standardGraph().dependencies("db")).isEmpty();
    }

    @Test
    @DisplayName("services are ranked by how much breaks when they do, not by how deep they sit")
    void criticalServicesRankedByBlastRadius() {
        List<ServiceGraph.BlastRadius> critical = standardGraph().criticalServices(5);

        // db is under everything, so it ranks first with four services above it.
        assertThat(critical.get(0).serviceId()).isEqualTo("db");
        assertThat(critical.get(0).size()).isEqualTo(4);

        // auth and catalog each have api and web above them, so both outrank api,
        // which only has web. Position in the topology is not the same as
        // criticality: api sits higher up and matters less.
        assertThat(critical).extracting(ServiceGraph.BlastRadius::serviceId)
                .containsExactly("db", "auth", "catalog", "api");
        assertThat(critical.get(3).size()).isEqualTo(1);
    }

    @Test
    @DisplayName("a single point of failure is found by removing it and seeing what is cut off")
    void singlePointOfFailureDetected() {
        List<ServiceGraph.SinglePointOfFailure> spofs = standardGraph().singlePointsOfFailure();

        // Removing api cuts auth, catalog and db off from the only entry point.
        assertThat(spofs.get(0).serviceId()).isEqualTo("api");
        assertThat(spofs.get(0).isolated()).containsExactlyInAnyOrder("auth", "catalog", "db");
    }

    @Test
    @DisplayName("a redundantly reachable service is not a single point of failure")
    void redundantPathIsNotASpof() {
        // db is reachable through both auth and catalog, so neither alone isolates it.
        List<ServiceGraph.SinglePointOfFailure> spofs = standardGraph().singlePointsOfFailure();

        assertThat(spofs).extracting(ServiceGraph.SinglePointOfFailure::serviceId)
                .doesNotContain("auth", "catalog");
    }

    @Test
    @DisplayName("the critical path is the longest chain of dependencies")
    void criticalPathIsTheLongestChain() {
        List<String> path = standardGraph().criticalPath();

        assertThat(path).hasSize(4);
        assertThat(path.get(0)).isEqualTo("web");
        assertThat(path.get(1)).isEqualTo("api");
        assertThat(path.get(3)).isEqualTo("db");
    }

    @Test
    @DisplayName("a cycle does not hang the traversal")
    void cyclesAreTraversedSafely() {
        Map<String, String> names = new LinkedHashMap<>(Map.of("a", "a", "b", "b", "c", "c"));
        ServiceGraph graph = new ServiceGraph(names,
                List.of(edge("a", "b"), edge("b", "c"), edge("c", "a")));

        assertThat(graph.blastRadius("a").affected()).containsExactlyInAnyOrder("b", "c");
        assertThat(graph.dependencies("a")).containsExactlyInAnyOrder("b", "c");
        assertThat(graph.criticalPath()).hasSize(3);
    }

    @Test
    @DisplayName("cyclic dependencies are reported, because they break assumptions elsewhere")
    void cyclesAreReported() {
        Map<String, String> names = new LinkedHashMap<>(Map.of("a", "a", "b", "b", "c", "c"));
        ServiceGraph graph = new ServiceGraph(names,
                List.of(edge("a", "b"), edge("b", "c"), edge("c", "a")));

        assertThat(graph.cycles()).isNotEmpty();
        assertThat(graph.cycles().get(0)).containsExactlyInAnyOrder("a", "b", "c");
    }

    @Test
    @DisplayName("a graph with no entry point yields no single points of failure rather than a guess")
    void pureCycleHasNoSpofs() {
        Map<String, String> names = new LinkedHashMap<>(Map.of("a", "a", "b", "b"));
        ServiceGraph graph = new ServiceGraph(names, List.of(edge("a", "b"), edge("b", "a")));

        assertThat(graph.entryPoints()).isEmpty();
        assertThat(graph.singlePointsOfFailure()).isEmpty();
    }

    @Test
    @DisplayName("a service with no edges is reported as isolated, not as healthy")
    void isolatedServicesAreVisible() {
        Map<String, String> names = new LinkedHashMap<>(Map.of("a", "a", "b", "b", "lonely", "lonely"));
        ServiceGraph graph = new ServiceGraph(names, List.of(edge("a", "b")));

        assertThat(graph.isolatedServices()).containsExactly("lonely");
    }

    @Test
    @DisplayName("an edge naming an unknown service is ignored rather than creating a phantom node")
    void unknownEdgeEndpointsAreIgnored() {
        Map<String, String> names = new LinkedHashMap<>(Map.of("a", "a"));
        ServiceGraph graph = new ServiceGraph(names, List.of(edge("a", "ghost")));

        assertThat(graph.serviceCount()).isEqualTo(1);
        assertThat(graph.dependencies("a")).isEmpty();
    }

    @Test
    @DisplayName("correlation names the upstream service as the one that explains the others")
    void correlationPicksTheUpstreamService() {
        ServiceGraph.Correlation correlation =
                standardGraph().correlate(Set.of("db", "auth", "api"));

        assertThat(correlation.likelyCauseId()).isEqualTo("db");
        assertThat(correlation.explainedSymptoms()).containsExactlyInAnyOrder("auth", "api");
        assertThat(correlation.unexplained()).isEmpty();
    }

    @Test
    @DisplayName("a degraded service that is not downstream is reported as needing its own explanation")
    void correlationAdmitsWhatItCannotExplain() {
        Map<String, String> names = new LinkedHashMap<>(Map.of(
                "web", "web", "api", "api", "db", "db", "unrelated", "unrelated"));
        ServiceGraph graph = new ServiceGraph(names,
                List.of(edge("web", "api"), edge("api", "db")));

        ServiceGraph.Correlation correlation = graph.correlate(Set.of("db", "api", "unrelated"));

        assertThat(correlation.likelyCauseId()).isEqualTo("db");
        assertThat(correlation.unexplained()).containsExactly("unrelated");
        assertThat(correlation.reason()).contains("need their own explanation");
    }

    @Test
    @DisplayName("independent failures are reported as independent, not forced into one cause")
    void correlationDoesNotInventACause() {
        Map<String, String> names = new LinkedHashMap<>(Map.of("a", "a", "b", "b"));
        ServiceGraph graph = new ServiceGraph(names, List.of());

        ServiceGraph.Correlation correlation = graph.correlate(Set.of("a", "b"));

        assertThat(correlation.likelyCauseId()).isNull();
        assertThat(correlation.reason()).contains("look independent");
    }

    @Test
    @DisplayName("200 services and 1000 edges stay within interactive latency")
    void meetsTheStatedScaleTarget() {
        Map<String, String> names = new LinkedHashMap<>();
        for (int i = 0; i < 200; i++) {
            names.put("svc-" + i, "svc-" + i);
        }

        // A layered graph: each service calls five others further down, which gives
        // roughly a thousand edges with no cycles and real depth.
        List<ServiceGraph.Edge> edges = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            for (int fanout = 1; fanout <= 5; fanout++) {
                int callee = i + fanout;
                if (callee < 200) {
                    edges.add(edge("svc-" + i, "svc-" + callee));
                }
            }
        }

        ServiceGraph graph = new ServiceGraph(names, edges);
        assertThat(graph.edgeCount()).isGreaterThan(950);

        long startedAt = System.nanoTime();
        graph.blastRadius("svc-199");
        graph.criticalServices(10);
        List<ServiceGraph.SinglePointOfFailure> spofs = graph.singlePointsOfFailure();
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(spofs).isNotNull();
        // The NFR is interactive latency; a whole second would already be a failure
        // for something the dashboard renders on load.
        assertThat(elapsedMs).isLessThan(1000);
    }
}
