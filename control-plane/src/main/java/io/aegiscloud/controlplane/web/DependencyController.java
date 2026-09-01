package io.aegiscloud.controlplane.web;

import io.aegiscloud.controlplane.graph.GraphStore;
import io.aegiscloud.controlplane.graph.ServiceGraph;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The dependency graph's HTTP surface (FR-22 to FR-26).
 *
 * <p>Reads are open to any authenticated user — knowing what calls what is exactly
 * the information an on-call engineer needs at three in the morning. Declaring or
 * removing an edge is OPERATOR+, because a wrong edge produces a confidently wrong
 * blast radius, and blast radius is what the Experiment Engine and Phase 8 both
 * reason from.
 */
@RestController
@RequestMapping("/api/v1")
public class DependencyController {

    private final GraphStore store;

    public DependencyController(GraphStore store) {
        this.store = store;
    }

    /** The whole graph: nodes, edges, and the structural facts worth knowing about it. */
    public record GraphView(
            int serviceCount,
            int edgeCount,
            List<GraphStore.EdgeRow> edges,
            List<String> entryPoints,
            List<String> isolatedServices,
            List<String> criticalPath,
            List<ServiceGraph.SinglePointOfFailure> singlePointsOfFailure,
            List<ServiceGraph.BlastRadius> mostCritical) {
    }

    @GetMapping("/graph")
    public GraphView graph() {
        ServiceGraph graph = store.load();

        return new GraphView(
                graph.serviceCount(),
                graph.edgeCount(),
                store.edgeRows(),
                graph.entryPoints().stream().map(graph::nameOf).toList(),
                graph.isolatedServices().stream().map(graph::nameOf).toList(),
                graph.criticalPathNames(),
                graph.singlePointsOfFailure(),
                graph.criticalServices(10));
    }

    /**
     * @param discoverySource MANUAL for an edge a person declared. TRACE and
     *                        EXPERIMENT are written by the platform itself and are
     *                        not accepted here — an edge claiming to have been proved
     *                        by an experiment that never ran would corrupt the one
     *                        signal Phase 8 is measured against.
     */
    public record DeclareEdgeRequest(
            @NotBlank String callerServiceId,
            @NotBlank String calleeServiceId,
            double callRatePerMin,
            double errorRatePct,
            double latencyP95Ms) {
    }

    @PostMapping("/graph/dependencies")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public GraphStore.EdgeRow declare(@Valid @RequestBody DeclareEdgeRequest request) {
        UUID caller = uuid(request.callerServiceId());
        UUID callee = uuid(request.calleeServiceId());

        store.serviceName(caller).orElseThrow(() ->
                ApiException.notFound("service " + caller + " not found"));
        store.serviceName(callee).orElseThrow(() ->
                ApiException.notFound("service " + callee + " not found"));

        try {
            store.upsertEdge(caller, callee, "MANUAL", request.callRatePerMin(),
                    request.errorRatePct(), request.latencyP95Ms());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }

        return store.edgeRows().stream()
                .filter(e -> e.callerServiceId().equals(caller.toString())
                        && e.calleeServiceId().equals(callee.toString()))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("edge was not stored"));
    }

    @DeleteMapping("/graph/dependencies/{callerServiceId}/{calleeServiceId}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public java.util.Map<String, Object> remove(@PathVariable String callerServiceId,
                                                @PathVariable String calleeServiceId) {
        int removed = store.deleteEdge(uuid(callerServiceId), uuid(calleeServiceId));
        if (removed == 0) {
            throw ApiException.notFound("no such dependency edge");
        }
        return java.util.Map.of("removed", removed);
    }

    /** What breaks when this service does (FR-24). */
    @GetMapping("/services/{serviceId}/blast-radius")
    public ServiceGraph.BlastRadius blastRadius(@PathVariable String serviceId) {
        ServiceGraph graph = store.load();
        String id = uuid(serviceId).toString();

        if (!graph.contains(id)) {
            throw ApiException.notFound("service " + serviceId + " not found");
        }
        return graph.blastRadius(id);
    }

    /** Where this service sits: who calls it, what it calls, how much depends on it. */
    @GetMapping("/services/{serviceId}/position")
    public ServiceGraph.ServicePosition position(@PathVariable String serviceId) {
        ServiceGraph graph = store.load();
        String id = uuid(serviceId).toString();

        if (!graph.contains(id)) {
            throw ApiException.notFound("service " + serviceId + " not found");
        }
        return graph.position(id);
    }

    /**
     * Which currently-degraded service best explains the others (FR-26).
     *
     * <p>A topology answer only. It says which position is consistent with what is
     * failing, not which service is at fault — that verdict needs timing and change
     * history, and arrives in Phase 8.
     *
     * @param scoreBelow the reliability score under which a service counts as degraded
     */
    @GetMapping("/graph/correlate")
    public ServiceGraph.Correlation correlate(@RequestParam(defaultValue = "80") double scoreBelow) {
        ServiceGraph graph = store.load();
        Set<String> degraded = Set.copyOf(store.degradedServiceIds(scoreBelow));
        return graph.correlate(degraded);
    }

    /** Cyclic dependencies, which break the assumptions every other answer here rests on. */
    @GetMapping("/graph/cycles")
    public List<List<String>> cycles() {
        ServiceGraph graph = store.load();
        return graph.cycles().stream()
                .map(cycle -> cycle.stream().map(graph::nameOf).toList())
                .toList();
    }

    private static UUID uuid(String raw) {
        try {
            return UUID.fromString(raw.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("not a valid service id: " + raw);
        }
    }
}
