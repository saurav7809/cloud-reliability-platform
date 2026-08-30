import type { ExperimentRun, Slo } from "../api/client";
import { Card, Badge, StatusBadge, Bar, Score, timeAgo } from "../components/ui";

function budgetTone(pct: number) {
  return pct >= 50 ? "good" : pct >= 20 ? "warn" : "bad";
}

export function ReliabilityPage({
  slos,
  experiments,
}: {
  slos: Slo[];
  experiments: ExperimentRun[];
}) {
  return (
    <>
      <div className="page-head">
        <h1>Reliability</h1>
        <p>
          SLO attainment and error-budget burn, plus the chaos experiments used to prove
          resilience before an incident does.
        </p>
      </div>

      <Card title="Service Level Objectives" meta={`${slos.length} defined`}>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Target</th>
                <th>SLI</th>
                <th>Objective</th>
                <th>Current</th>
                <th>Window</th>
                <th style={{ minWidth: 130 }}>Error Budget</th>
                <th>Burn Rate</th>
              </tr>
            </thead>
            <tbody>
              {slos.map((s) => {
                const met =
                  s.sliType === "LATENCY_P95"
                    ? s.currentValue <= s.objectiveValue
                    : s.currentValue >= s.objectiveValue;
                const unit = s.sliType === "LATENCY_P95" ? "ms" : "%";
                return (
                  <tr key={s.id}>
                    <td className="td-strong">{s.targetLabel}</td>
                    <td>
                      <Badge tone="mute">{s.sliType.replace(/_/g, " ")}</Badge>
                    </td>
                    <td className="mono">
                      {s.objectiveValue}
                      {unit}
                    </td>
                    <td className="mono" style={{ color: met ? "var(--good)" : "var(--bad)" }}>
                      {s.currentValue}
                      {unit}
                    </td>
                    <td className="mono">{s.windowDays}d</td>
                    <td>
                      <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                        <Bar pct={s.budgetRemainingPct} tone={budgetTone(s.budgetRemainingPct)} />
                        <span className="mono" style={{ minWidth: 34 }}>
                          {s.budgetRemainingPct.toFixed(0)}%
                        </span>
                      </div>
                    </td>
                    <td>
                      <span
                        className="mono"
                        style={{
                          color:
                            s.burnRate >= 2
                              ? "var(--bad)"
                              : s.burnRate >= 1
                                ? "var(--warn)"
                                : "var(--good)",
                          fontWeight: 600,
                        }}
                      >
                        {s.burnRate.toFixed(1)}×
                      </span>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </Card>

      <div style={{ marginTop: 14 }}>
        <Card title="Chaos Experiments" meta="before / during / after impact">
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Service</th>
                  <th>Target</th>
                  <th>Fault</th>
                  <th>Before</th>
                  <th>During</th>
                  <th>After</th>
                  <th>Started</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {experiments.map((e) => {
                  const rejected = e.status === "REJECTED_BY_POLICY";
                  return (
                    <tr key={e.id}>
                      <td className="td-strong">{e.serviceName}</td>
                      <td>{e.targetLabel}</td>
                      <td>
                        <Badge tone="warn">{e.faultType.replace(/_/g, " ")}</Badge>
                      </td>
                      <td>{rejected ? "—" : <Score value={e.scoreBefore} />}</td>
                      <td>{rejected ? "—" : <Score value={e.scoreDuring} />}</td>
                      <td>
                        {rejected || e.status === "RUNNING" ? (
                          "—"
                        ) : (
                          <Score value={e.scoreAfter} />
                        )}
                      </td>
                      <td style={{ color: "var(--text-dim)" }}>{timeAgo(e.startedAt)}</td>
                      <td>
                        <StatusBadge status={e.status} />
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </Card>
      </div>
    </>
  );
}
