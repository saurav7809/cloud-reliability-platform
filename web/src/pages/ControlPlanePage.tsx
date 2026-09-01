import type { HealingEvent, ScalingEvent } from "../api/client";
import { Card, Badge, StatusBadge, timeAgo } from "../components/ui";
import { LiveActivity } from "../components/LiveActivity";

export function ControlPlanePage({
  scaling,
  healing,
  token,
}: {
  scaling: ScalingEvent[];
  healing: HealingEvent[];
  token: string;
}) {
  return (
    <>
      <div className="page-head">
        <h1>Control Plane</h1>
        <p>
          Auto-Scaling and Self-Healing act continuously; every action passes the Policy
          Engine's guardrails first.
        </p>
      </div>

      <LiveActivity token={token} />

      <Card title="Auto-Scaling Decisions" meta={`${scaling.length} events`}>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Target</th>
                <th>Change</th>
                <th>Strategy</th>
                <th>Trigger</th>
                <th>Value</th>
                <th>When</th>
              </tr>
            </thead>
            <tbody>
              {scaling.map((s) => {
                const up = s.newReplicas > s.previousReplicas;
                return (
                  <tr key={s.id}>
                    <td className="td-strong">{s.targetLabel}</td>
                    <td className="mono">
                      {s.previousReplicas} →{" "}
                      <span style={{ color: up ? "var(--good)" : "var(--warn)" }}>
                        {s.newReplicas}
                      </span>{" "}
                      {up ? "▲" : "▼"}
                    </td>
                    <td>
                      <Badge tone="info">{s.strategy}</Badge>
                    </td>
                    <td className="mono">{s.triggerMetric}</td>
                    <td className="mono">{s.triggerValue}</td>
                    <td style={{ color: "var(--text-dim)" }}>{timeAgo(s.decidedAt)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </Card>

      <div style={{ marginTop: 14 }}>
        <Card title="Self-Healing Actions" meta={`${healing.length} events`}>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Target</th>
                  <th>Pod</th>
                  <th>Reason</th>
                  <th>Action</th>
                  <th>Detected</th>
                  <th>State</th>
                </tr>
              </thead>
              <tbody>
                {healing.map((h) => (
                  <tr key={h.id}>
                    <td className="td-strong">{h.targetLabel}</td>
                    <td className="mono">{h.podName}</td>
                    <td>
                      <Badge tone="bad">{h.reason.replace(/_/g, " ")}</Badge>
                    </td>
                    <td>
                      <Badge tone="info">{h.actionTaken}</Badge>
                    </td>
                    <td style={{ color: "var(--text-dim)" }}>{timeAgo(h.detectedAt)}</td>
                    <td>
                      <StatusBadge status={h.resolvedAt ? "RESOLVED" : "RUNNING"} />
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
