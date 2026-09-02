import { useCallback, useEffect, useState } from "react";
import {
  getAlerts,
  getClusters,
  getExperiments,
  getHealingEvents,
  getOverview,
  getPolicies,
  getScalingEvents,
  getServices,
  getSlos,
  getTargets,
  me,
  type Alert,
  type Cluster,
  type DeploymentTarget,
  type ExperimentRun,
  type HealingEvent,
  type MeResponse,
  type Overview,
  type Policy,
  type ScalingEvent,
  type Service,
  type Slo,
} from "../api/client";
import { Brand } from "./ui";
import { OverviewPage } from "../pages/OverviewPage";
import { ClustersPage } from "../pages/ClustersPage";
import { ServicesPage } from "../pages/ServicesPage";
import { ControlPlanePage } from "../pages/ControlPlanePage";
import { ReliabilityPage } from "../pages/ReliabilityPage";
import { AlertsPage } from "../pages/AlertsPage";
import { MicroservicesPage } from "../pages/MicroservicesPage";

type Tab =
  | "overview"
  | "clusters"
  | "microservices"
  | "services"
  | "control"
  | "reliability"
  | "alerts";

const NAV: { id: Tab; label: string; icon: string }[] = [
  // Ordered as the platform works: register and run a service first, then the
  // capabilities that only mean anything once one is running.
  { id: "microservices", label: "Microservices", icon: "⬢" },
  { id: "overview", label: "Overview", icon: "◎" },
  { id: "clusters", label: "Clusters", icon: "▦" },
  { id: "services", label: "Fleet detail", icon: "◈" },
  { id: "control", label: "Control Plane", icon: "⟳" },
  { id: "reliability", label: "Reliability", icon: "◔" },
  { id: "alerts", label: "Alerts", icon: "△" },
];

interface Data {
  overview: Overview;
  clusters: Cluster[];
  services: Service[];
  targets: DeploymentTarget[];
  slos: Slo[];
  policies: Policy[];
  alerts: Alert[];
  experiments: ExperimentRun[];
  scaling: ScalingEvent[];
  healing: HealingEvent[];
}

export function Dashboard({
  token,
  onLogout,
}: {
  token: string;
  onLogout: () => void;
}) {
  const [tab, setTab] = useState<Tab>("microservices");
  const [profile, setProfile] = useState<MeResponse | null>(null);
  const [data, setData] = useState<Data | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const [
        overview,
        clusters,
        services,
        targets,
        slos,
        policies,
        alerts,
        experiments,
        scaling,
        healing,
      ] = await Promise.all([
        getOverview(token),
        getClusters(token),
        getServices(token),
        getTargets(token),
        getSlos(token),
        getPolicies(token),
        getAlerts(token),
        getExperiments(token),
        getScalingEvents(token),
        getHealingEvents(token),
      ]);
      setData({
        overview,
        clusters,
        services,
        targets,
        slos,
        policies,
        alerts,
        experiments,
        scaling,
        healing,
      });
    } catch {
      setError("Session expired or backend unreachable. Try signing in again.");
    }
  }, [token]);

  useEffect(() => {
    me(token).then(setProfile).catch(() => setError("Session expired."));
    load();
  }, [token, load]);

  const openAlerts = data?.alerts.filter((a) => a.status === "OPEN").length ?? 0;

  return (
    <div className="layout">
      <aside className="sidebar">
        <Brand size={26} />

        <nav className="nav">
          {NAV.map((n) => (
            <button
              key={n.id}
              className={`nav-item ${tab === n.id ? "active" : ""}`}
              onClick={() => setTab(n.id)}
            >
              <span aria-hidden="true" style={{ width: 15 }}>
                {n.icon}
              </span>
              {n.label}
              {n.id === "alerts" && openAlerts > 0 && (
                <span className="nav-count">{openAlerts}</span>
              )}
            </button>
          ))}
        </nav>

        <div className="sidebar-foot">
          <div className="avatar">
            {(profile?.email ?? "?").charAt(0).toUpperCase()}
          </div>
          <div className="who">
            <div className="who-email">{profile?.email ?? "…"}</div>
            <div className="who-role">{profile?.role ?? ""}</div>
          </div>
          <button className="btn btn-ghost" onClick={onLogout}>
            Exit
          </button>
        </div>
      </aside>

      <main className="main">
        {error && <p className="error-msg">{error}</p>}

        {!data && !error && <div className="loading">Loading platform data…</div>}

        {data && (
          <>
            {tab === "overview" && <OverviewPage data={data.overview} />}
            {tab === "clusters" && (
              <ClustersPage clusters={data.clusters} policies={data.policies} />
            )}
            {tab === "microservices" && (
              <MicroservicesPage token={token} clusters={data.clusters} />
            )}
            {tab === "services" && (
              <ServicesPage services={data.services} targets={data.targets} />
            )}
            {tab === "control" && (
              <ControlPlanePage scaling={data.scaling} healing={data.healing} token={token} />
            )}
            {tab === "reliability" && (
              <ReliabilityPage slos={data.slos} experiments={data.experiments} />
            )}
            {tab === "alerts" && (
              <AlertsPage alerts={data.alerts} token={token} onChanged={load} />
            )}
          </>
        )}
      </main>
    </div>
  );
}
