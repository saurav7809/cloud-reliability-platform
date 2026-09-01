package io.aegiscloud.controlplane.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The service dependency graph and the questions asked of it (FR-22 to FR-25).
 *
 * <p>An immutable snapshot with no database or cluster underneath it, which is what
 * makes every algorithm here testable against a graph drawn by hand — including the
 * cases that matter and rarely occur naturally: cycles, disconnected components, a
 * service that depends on nothing, a service nothing depends on.
 *
 * <p>Direction is the thing to keep straight. An edge {@code A -> B} means "A calls
 * B", so failure travels <em>backwards</em> along edges: when B breaks, it is A that
 * suffers. Blast radius therefore walks the reverse graph, and getting that
 * backwards would produce an answer that is confidently and exactly wrong.
 */
public final class ServiceGraph {

    private final Map<String, String> names;
    /** callee -> callers, the direction failure propagates. */
    private final Map<String, Set<String>> callers = new HashMap<>();
    /** caller -> callees, the direction requests travel. */
    private final Map<String, Set<String>> callees = new HashMap<>();
    private final List<Edge> edges;

    /**
     * @param callRatePerMin how much traffic crosses this edge, which separates a
     *                       dependency the caller leans on from one it barely uses
     */
    public record Edge(
            String callerServiceId,
            String calleeServiceId,
            String discoverySource,
            double callRatePerMin,
            double errorRatePct,
            double latencyP95Ms) {
    }

    public ServiceGraph(Map<String, String> serviceNames, List<Edge> edges) {
        this.names = Map.copyOf(serviceNames);
        this.edges = List.copyOf(edges);

        for (String id : serviceNames.keySet()) {
            callers.put(id, new LinkedHashSet<>());
            callees.put(id, new LinkedHashSet<>());
        }
        for (Edge edge : edges) {
            // An edge naming a service the graph does not know about is skipped
            // rather than silently creating a phantom node with no name.
            if (!callers.containsKey(edge.calleeServiceId()) || !callees.containsKey(edge.callerServiceId())) {
                continue;
            }
            callers.get(edge.calleeServiceId()).add(edge.callerServiceId());
            callees.get(edge.callerServiceId()).add(edge.calleeServiceId());
        }
    }

    public int serviceCount() {
        return names.size();
    }

    public int edgeCount() {
        return edges.size();
    }

    public List<Edge> edges() {
        return edges;
    }

    public String nameOf(String serviceId) {
        return names.getOrDefault(serviceId, serviceId);
    }

    public boolean contains(String serviceId) {
        return names.containsKey(serviceId);
    }

    /**
     * Everything that suffers when this service fails (FR-24).
     *
     * @param depth how many hops from the failure each affected service sits, so a
     *              direct caller can be told apart from something three levels away
     *              that will degrade later and less
     */
    public record BlastRadius(String serviceId, String serviceName, Set<String> affected,
                              Map<String, Integer> depth) {

        public int size() {
            return affected.size();
        }
    }

    /** The transitive set of callers of a service — who breaks when it does. */
    public BlastRadius blastRadius(String serviceId) {
        Map<String, Integer> depth = traverse(serviceId, callers);
        depth.remove(serviceId);
        return new BlastRadius(serviceId, nameOf(serviceId),
                new LinkedHashSet<>(depth.keySet()), depth);
    }

    /** The transitive set of services this one depends on. */
    public Set<String> dependencies(String serviceId) {
        Map<String, Integer> depth = traverse(serviceId, callees);
        depth.remove(serviceId);
        return new LinkedHashSet<>(depth.keySet());
    }

    /**
     * Breadth-first traversal that records distance and tolerates cycles.
     *
     * <p>Cycles are not hypothetical in real architectures — two services calling
     * each other is common enough — and an algorithm that recurses without a visited
     * set will hang the request thread rather than return a wrong answer, which is
     * worse.
     */
    private Map<String, Integer> traverse(String from, Map<String, Set<String>> adjacency) {
        Map<String, Integer> depth = new LinkedHashMap<>();
        if (!names.containsKey(from)) {
            return depth;
        }

        Deque<String> queue = new ArrayDeque<>();
        queue.add(from);
        depth.put(from, 0);

        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            for (String next : adjacency.getOrDefault(current, Set.of())) {
                if (!depth.containsKey(next)) {
                    depth.put(next, depth.get(current) + 1);
                    queue.addLast(next);
                }
            }
        }
        return depth;
    }

    /** Services ranked by how much breaks when they do. */
    public List<BlastRadius> criticalServices(int limit) {
        return names.keySet().stream()
                .map(this::blastRadius)
                .filter(radius -> radius.size() > 0)
                .sorted(Comparator.comparingInt(BlastRadius::size).reversed()
                        .thenComparing(BlastRadius::serviceName))
                .limit(limit)
                .toList();
    }

    /**
     * A service whose failure cuts others off from the system's entry points.
     *
     * @param isolated services that can no longer be reached from any entry point
     *                 once this one is gone
     */
    public record SinglePointOfFailure(String serviceId, String serviceName,
                                       Set<String> isolated, String reason) {
    }

    /**
     * Single points of failure (FR-25).
     *
     * <p>Computed by removal rather than by counting callers: a service with three
     * callers is not redundant, and a service with one caller is not necessarily
     * critical. The question that matters is whether anything becomes unreachable
     * from an entry point when this service is gone, and the only honest way to
     * answer it is to take the service out and look.
     *
     * <p>Entry points are services nothing calls — the edges of the system, where
     * user traffic arrives. A graph whose every node has a caller (a pure cycle) has
     * no entry point, and no meaningful answer here; that case returns nothing rather
     * than an arbitrary one.
     */
    public List<SinglePointOfFailure> singlePointsOfFailure() {
        Set<String> entryPoints = entryPoints();
        if (entryPoints.isEmpty()) {
            return List.of();
        }

        Set<String> reachableNormally = reachableFrom(entryPoints, Set.of());
        List<SinglePointOfFailure> found = new ArrayList<>();

        for (String candidate : names.keySet()) {
            if (entryPoints.contains(candidate)) {
                // An entry point failing takes out only itself and its own subtree
                // by definition; that is not a hidden single point of failure, it
                // is the front door.
                continue;
            }

            Set<String> reachableWithout = reachableFrom(entryPoints, Set.of(candidate));

            Set<String> isolated = new LinkedHashSet<>(reachableNormally);
            isolated.removeAll(reachableWithout);
            isolated.remove(candidate);

            if (!isolated.isEmpty()) {
                found.add(new SinglePointOfFailure(candidate, nameOf(candidate), isolated,
                        "removing " + nameOf(candidate) + " cuts " + isolated.size()
                                + " service(s) off from every entry point"));
            }
        }

        found.sort(Comparator.comparingInt((SinglePointOfFailure s) -> s.isolated().size())
                .reversed().thenComparing(SinglePointOfFailure::serviceName));
        return found;
    }

    /** Services nothing calls: where traffic enters the system. */
    public Set<String> entryPoints() {
        return names.keySet().stream()
                .filter(id -> callers.getOrDefault(id, Set.of()).isEmpty())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> reachableFrom(Set<String> roots, Set<String> excluded) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();

        for (String root : roots) {
            if (!excluded.contains(root) && seen.add(root)) {
                queue.add(root);
            }
        }

        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            for (String next : callees.getOrDefault(current, Set.of())) {
                if (!excluded.contains(next) && seen.add(next)) {
                    queue.addLast(next);
                }
            }
        }
        return seen;
    }

    /**
     * The longest dependency chain in the graph — the critical path (FR-25).
     *
     * <p>Depth is what a request pays for: every hop is another service that has to
     * be up and another latency to add. The longest chain is where that cost is
     * highest and where a single slow service has the most company.
     *
     * <p>Cycles are excluded from a path rather than allowed to extend it forever: a
     * chain that visits a service twice is not a longer chain, it is a loop.
     */
    public List<String> criticalPath() {
        List<String> longest = List.of();

        for (String start : names.keySet()) {
            List<String> path = longestPathFrom(start, new LinkedHashSet<>());
            if (path.size() > longest.size()) {
                longest = path;
            }
        }
        return longest;
    }

    private List<String> longestPathFrom(String current, Set<String> onPath) {
        if (!onPath.add(current)) {
            return List.of();
        }

        List<String> best = List.of();
        for (String next : callees.getOrDefault(current, Set.of())) {
            List<String> candidate = longestPathFrom(next, onPath);
            if (candidate.size() > best.size()) {
                best = candidate;
            }
        }

        onPath.remove(current);

        List<String> path = new ArrayList<>();
        path.add(current);
        path.addAll(best);
        return path;
    }

    /** The critical path rendered with service names, for display. */
    public List<String> criticalPathNames() {
        return criticalPath().stream().map(this::nameOf).toList();
    }

    /**
     * Which of the degraded services best explains the rest (FR-26).
     *
     * <p>Given several services failing at once, the one that sits upstream of the
     * others — the one whose blast radius covers them — is the likelier cause, and
     * the rest are its symptoms. This is a graph-shaped answer only, deliberately: it
     * says which position in the topology is consistent with the observations, not
     * which service is at fault. Phase 8 adds timing and change history on top; a
     * verdict from topology alone would be a guess with a diagram attached.
     *
     * @param degraded services observed failing together
     */
    public record Correlation(String likelyCauseId, String likelyCauseName,
                              Set<String> explainedSymptoms, Set<String> unexplained,
                              String reason) {
    }

    public Correlation correlate(Set<String> degraded) {
        if (degraded.isEmpty()) {
            return new Correlation(null, null, Set.of(), Set.of(), "nothing is degraded");
        }

        String bestCandidate = null;
        Set<String> bestExplained = Set.of();

        for (String candidate : degraded) {
            if (!contains(candidate)) {
                continue;
            }
            Set<String> downstreamOfCandidate = blastRadius(candidate).affected();

            Set<String> explained = new LinkedHashSet<>(degraded);
            explained.retainAll(downstreamOfCandidate);

            if (explained.size() > bestExplained.size()) {
                bestExplained = explained;
                bestCandidate = candidate;
            }
        }

        if (bestCandidate == null) {
            return new Correlation(null, null, Set.of(), degraded,
                    "no degraded service sits upstream of any other; these look independent");
        }

        Set<String> unexplained = new LinkedHashSet<>(degraded);
        unexplained.remove(bestCandidate);
        unexplained.removeAll(bestExplained);

        return new Correlation(bestCandidate, nameOf(bestCandidate), bestExplained, unexplained,
                nameOf(bestCandidate) + " is upstream of " + bestExplained.size()
                        + " of the " + degraded.size() + " degraded services"
                        + (unexplained.isEmpty() ? "" : "; " + unexplained.size()
                        + " are not downstream of it and need their own explanation"));
    }

    /** Everything about one service's position, for the API and the dashboard. */
    public record ServicePosition(String serviceId, String serviceName, Set<String> directCallers,
                                  Set<String> directCallees, int blastRadiusSize, boolean entryPoint) {
    }

    public ServicePosition position(String serviceId) {
        return new ServicePosition(serviceId, nameOf(serviceId),
                Set.copyOf(callers.getOrDefault(serviceId, Set.of())),
                Set.copyOf(callees.getOrDefault(serviceId, Set.of())),
                blastRadius(serviceId).size(),
                callers.getOrDefault(serviceId, Set.of()).isEmpty());
    }

    /** Services with no edges at all — usually a gap in discovery rather than a fact. */
    public Set<String> isolatedServices() {
        Set<String> isolated = new LinkedHashSet<>();
        for (String id : names.keySet()) {
            if (callers.getOrDefault(id, Set.of()).isEmpty()
                    && callees.getOrDefault(id, Set.of()).isEmpty()) {
                isolated.add(id);
            }
        }
        return isolated;
    }

    /** Cyclic dependencies, which are worth surfacing because they break assumptions elsewhere. */
    public List<List<String>> cycles() {
        List<List<String>> found = new ArrayList<>();
        Set<String> globallySeen = new HashSet<>();

        for (String start : names.keySet()) {
            if (globallySeen.contains(start)) {
                continue;
            }
            List<String> path = new ArrayList<>();
            findCycle(start, start, new LinkedHashSet<>(), path, found, globallySeen);
        }
        return found;
    }

    private boolean findCycle(String start, String current, Set<String> onPath, List<String> path,
                              List<List<String>> found, Set<String> globallySeen) {
        if (!onPath.add(current)) {
            if (current.equals(start) && path.size() > 1) {
                found.add(List.copyOf(path));
                globallySeen.addAll(path);
                return true;
            }
            return false;
        }
        path.add(current);

        for (String next : callees.getOrDefault(current, Set.of())) {
            if (next.equals(start) && path.size() > 1) {
                found.add(List.copyOf(path));
                globallySeen.addAll(path);
                onPath.remove(current);
                path.remove(path.size() - 1);
                return true;
            }
            if (findCycle(start, next, onPath, path, found, globallySeen)) {
                onPath.remove(current);
                path.remove(path.size() - 1);
                return true;
            }
        }

        onPath.remove(current);
        path.remove(path.size() - 1);
        return false;
    }
}
