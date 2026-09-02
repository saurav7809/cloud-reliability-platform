import { useCallback, useEffect, useState } from "react";
import { ApiError, getGraph, type GraphView } from "../api/client";
import { Badge, Card } from "../components/ui";

const SOURCE_TONE: Record<string, "good" | "warn" | "info"> = {
  // An edge proved by breaking a service is stronger evidence than one declared or
  // inferred from traffic, and the colour says so.
  EXPERIMENT: "good",
  TRACE: "info",
  MANUAL: "warn",
};

/**
 * The dependency graph and what it implies.
 *
 * Direction is stated in the header rather than left to be inferred: an edge means
 * "calls", so failure travels the other way, and a reader who has that backwards will
 * misread every blast radius on the page.
 */
export function GraphPage({ token }: { token: string }) {
  const [graph, setGraph] = useState<GraphView | null>(null);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      setGraph(await getGraph(token));
      setError(null);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not load the graph");
    }
  }, [token]);

  useEffect(() => {
    refresh();
    const timer = setInterval(refresh, 30000);
    return () => clearInterval(timer);
  }, [refresh]);

  return (
    <>
      <div className="page-head">
        <h1>Dependencies</h1>
        <p>
          An edge means <em>calls</em>, so failure travels the other way: when a service
          at the bottom breaks, everything above it suffers. Edges marked{" "}
          <strong>experiment</strong> were proved by taking a service down and watching
          what degraded — the rest were declared.
        </p>
      </div>

      {error && <p className="error-msg">{error}</p>}

      {graph && (
        <>
          <Card
            title="Shape"
            meta={`${graph.serviceCount} services, ${graph.edgeCount} edges`}
          >
            <div className="graph-facts">
              <div>
                <h4>Entry points</h4>
                <p className="muted small">Where traffic arrives — nothing calls these.</p>
                <p>{graph.entryPoints.join(", ") || "none"}</p>
              </div>
              <div>
                <h4>Critical path</h4>
                <p className="muted small">
                  The longest chain: every hop is another service that must be up.
                </p>
                <p className="mono small">
                  {graph.criticalPath.join(" → ") || "no chain"}
                </p>
              </div>
              <div>
                <h4>Not connected</h4>
                <p className="muted small">
                  Usually a gap in discovery rather than a fact about the system.
                </p>
                <p>{graph.isolatedServices.join(", ") || "none"}</p>
              </div>
            </div>
          </Card>

          <Card
            title="Single points of failure"
            meta={`${graph.singlePointsOfFailure.length} found`}
          >
            <p className="muted">
              Found by removing each service and seeing what can no longer be reached
              from any entry point — not by counting callers. A service with three
              callers is not thereby redundant.
            </p>
            {graph.singlePointsOfFailure.map((s) => (
              <p key={s.serviceName} className="spof">
                <strong>{s.serviceName}</strong> — {s.reason}
              </p>
            ))}
            {graph.singlePointsOfFailure.length === 0 && (
              <p className="muted">
                Nothing is a single point of failure: every service stays reachable when
                any one other is removed.
              </p>
            )}
          </Card>

          <Card title="Blast radius" meta="what breaks when this does">
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Service</th>
                    <th>Services affected</th>
                    <th>Which</th>
                  </tr>
                </thead>
                <tbody>
                  {graph.mostCritical.map((b) => (
                    <tr key={b.serviceName}>
                      <td className="td-strong">{b.serviceName}</td>
                      <td className="mono">{b.affected.length}</td>
                      <td className="small">{b.affected.join(", ")}</td>
                    </tr>
                  ))}
                  {graph.mostCritical.length === 0 && (
                    <tr>
                      <td colSpan={3} className="muted">
                        No service has anything downstream of it yet.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </Card>

          <Card title="Edges" meta={`${graph.edges.length}`}>
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Caller</th>
                    <th>Callee</th>
                    <th>How it was found</th>
                    <th>Calls/min</th>
                    <th>p95</th>
                  </tr>
                </thead>
                <tbody>
                  {graph.edges.map((e, i) => (
                    <tr key={i}>
                      <td className="td-strong">{e.callerName}</td>
                      <td>{e.calleeName}</td>
                      <td>
                        <Badge tone={SOURCE_TONE[e.discoverySource] ?? "info"}>
                          {e.discoverySource.toLowerCase()}
                        </Badge>
                      </td>
                      <td className="mono small">{e.callRatePerMin || "—"}</td>
                      <td className="mono small">
                        {e.latencyP95Ms ? `${e.latencyP95Ms}ms` : "—"}
                      </td>
                    </tr>
                  ))}
                  {graph.edges.length === 0 && (
                    <tr>
                      <td colSpan={5} className="muted">
                        No edges yet. Declare one, or run a dependency-outage experiment
                        and let the platform prove them.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </Card>
        </>
      )}
    </>
  );
}
