import type { DeploymentTarget, Service } from "../api/client";
import {
  Card,
  ProviderTag,
  Score,
  StatusBadge,
  Badge,
  money,
} from "../components/ui";

export function ServicesPage({
  services,
  targets,
}: {
  services: Service[];
  targets: DeploymentTarget[];
}) {
  return (
    <>
      <div className="page-head">
        <h1>Services &amp; Targets</h1>
        <p>
          A deployment target is one service running on one cluster — the unit AegisCloud
          scales, heals, evaluates and scores.
        </p>
      </div>

      <div className="grid cols-3">
        {services.map((s) => {
          const own = targets.filter((t) => t.serviceName === s.name);
          const avg = own.length
            ? own.reduce((a, t) => a + t.reliabilityScore, 0) / own.length
            : 0;
          return (
            <Card key={s.id}>
              <div
                style={{
                  display: "flex",
                  justifyContent: "space-between",
                  alignItems: "start",
                  gap: 10,
                }}
              >
                <div>
                  <div style={{ color: "var(--text-strong)", fontWeight: 600 }}>
                    {s.name}
                  </div>
                  <div style={{ fontSize: 12, color: "var(--text-dim)", marginTop: 2 }}>
                    {s.ownerTeam}
                  </div>
                </div>
                <Score value={avg} />
              </div>
              <p style={{ fontSize: 12.5, margin: "10px 0 12px" }}>{s.description}</p>
              <div style={{ display: "flex", gap: 6, flexWrap: "wrap" }}>
                {Object.entries(s.tags).map(([k, v]) => (
                  <Badge key={k} tone="mute">
                    {k}={v}
                  </Badge>
                ))}
                <Badge tone="info">
                  {own.length} target{own.length === 1 ? "" : "s"}
                </Badge>
              </div>
            </Card>
          );
        })}
      </div>

      <div style={{ marginTop: 14 }}>
        <Card title="Deployment Targets" meta={`${targets.length} total`}>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Service</th>
                  <th>Cluster</th>
                  <th>Provider</th>
                  <th>Namespace</th>
                  <th>Strategy</th>
                  <th>Replicas</th>
                  <th>Avail %</th>
                  <th>p95</th>
                  <th>Err %</th>
                  <th>Cost/mo</th>
                  <th>Score</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {targets.map((t) => (
                  <tr key={t.id}>
                    <td className="td-strong">{t.serviceName}</td>
                    <td>{t.clusterName}</td>
                    <td>
                      <ProviderTag provider={t.provider} />
                    </td>
                    <td className="mono">{t.namespace}</td>
                    <td>
                      <Badge tone="mute">{t.scalingStrategy}</Badge>
                    </td>
                    <td className="mono">
                      {t.replicas}
                      {t.replicas !== t.desiredReplicas && (
                        <span style={{ color: "var(--warn)" }}> / {t.desiredReplicas}</span>
                      )}
                    </td>
                    <td className="mono">{t.availabilityPct.toFixed(2)}</td>
                    <td className="mono">{t.latencyP95Ms.toFixed(0)}ms</td>
                    <td className="mono">{t.errorRatePct.toFixed(2)}</td>
                    <td className="mono">{money(t.monthlyCostUsd)}</td>
                    <td>
                      <Score value={t.reliabilityScore} />
                    </td>
                    <td>
                      <StatusBadge status={t.status} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      </div>
    </>
  );
}
