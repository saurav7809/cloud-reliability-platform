import type { Overview } from "../api/client";
import { Card, Stat, StatusBadge, money, timeAgo, DemoNote } from "../components/ui";
import { TrendChart, ProviderBars } from "../components/Charts";

export function OverviewPage({ data }: { data: Overview }) {
  return (
    <>
      <div className="page-head">
        <h1>Fleet Overview</h1>
        <p>
          Reliability posture across {data.totalClusters} clusters and{" "}
          {data.totalTargets} deployment targets.
        </p>
      </div>

      <DemoNote>
        <strong>Seeded demo fleet.</strong> Clusters, services and metrics below are
        generated sample data served from the real API contract. Live readings begin in
        Phase&nbsp;3, when the Deployment Engine connects to clusters via client-go.
      </DemoNote>

      <div className="grid stat-row">
        <Stat
          label="Avg Reliability"
          value={data.avgScore.toFixed(1)}
          sub="across all targets"
        />
        <Stat
          label="Clusters"
          value={`${data.healthyClusters}/${data.totalClusters}`}
          sub={
            data.healthyClusters === data.totalClusters
              ? "all healthy"
              : `${data.totalClusters - data.healthyClusters} degraded`
          }
          tone={data.healthyClusters === data.totalClusters ? "good" : "warn"}
        />
        <Stat label="Running Replicas" value={data.totalReplicas} sub={`${data.totalServices} services`} />
        <Stat
          label="Open Alerts"
          value={data.openAlerts}
          sub={data.openAlerts ? "needs attention" : "all clear"}
          tone={data.openAlerts ? "bad" : "good"}
        />
        <Stat label="Monthly Spend" value={money(data.monthlyCostUsd)} sub="all providers" />
      </div>

      <div className="grid cols-2">
        <Card title="Reliability Trend" meta="last 14 days">
          <TrendChart points={data.scoreTrend} />
        </Card>

        <Card title="Score by Provider" meta="cross-cloud comparison">
          <ProviderBars data={data.scoreByProvider} />
          <div className="legend">
            {data.scoreByProvider.map((p) => (
              <span className="legend-item" key={p.provider}>
                {p.provider} · {money(p.costUsd)}/mo
              </span>
            ))}
          </div>
        </Card>
      </div>

      <div className="grid cols-2" style={{ marginTop: 14 }}>
        <Card title="Control Plane Engines">
          {data.engineStatus.map((e) => (
            <div className="engine-item" key={e.name}>
              <div className="row-main">
                <div className="row-title">{e.name}</div>
                <div className="row-sub">{e.detail}</div>
              </div>
              <span className="mono" style={{ color: "var(--text-dim)" }}>
                {e.actionsLast24h}
              </span>
              <StatusBadge status={e.status} />
            </div>
          ))}
        </Card>

        <div className="grid" style={{ gap: 14, alignContent: "start" }}>
          <Card title="Recent Scaling Decisions">
            <div className="rows">
              {data.recentScaling.map((s) => (
                <div className="row-item" key={s.id}>
                  <div className="row-main">
                    <div className="row-title">
                      {s.previousReplicas} → {s.newReplicas} replicas
                    </div>
                    <div className="row-sub">
                      {s.targetLabel} · {s.triggerMetric} {s.triggerValue}
                    </div>
                  </div>
                  <span className="row-time">{timeAgo(s.decidedAt)}</span>
                </div>
              ))}
            </div>
          </Card>

          <Card title="Observability Sources">
            {data.observabilityFeed.map((o) => (
              <div className="engine-item" key={o.name}>
                <div className="row-main">
                  <div className="row-title">{o.name}</div>
                  <div className="row-sub">
                    {o.kind} · {o.ingestRate}
                  </div>
                </div>
                <StatusBadge status={o.status} />
              </div>
            ))}
          </Card>
        </div>
      </div>
    </>
  );
}
