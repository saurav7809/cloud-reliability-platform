const API_URL = import.meta.env.VITE_API_URL ?? "http://localhost:8080";

export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const res = await fetch(`${API_URL}${path}`, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...options.headers,
    },
  });

  if (!res.ok) {
    const body = await res.json().catch(() => ({ message: res.statusText }));
    throw new ApiError(res.status, body.message ?? "Request failed");
  }

  return res.json() as Promise<T>;
}

function authed<T>(path: string, token: string, options: RequestInit = {}): Promise<T> {
  return request<T>(path, {
    ...options,
    headers: { ...options.headers, Authorization: `Bearer ${token}` },
  });
}

/* ---------------------------------- types --------------------------------- */

export type Provider = "AWS" | "GCP" | "AZURE" | "KIND" | "ON_PREM";

export interface LoginResponse {
  accessToken: string;
  role: string;
}

export interface MeResponse {
  email: string;
  role: string;
}

export interface Cluster {
  id: string;
  name: string;
  provider: Provider;
  distribution: string;
  region: string;
  status: "HEALTHY" | "DEGRADED" | "UNREACHABLE";
  nodeCount: number;
  k8sVersion: string;
  isLocal: boolean;
}

export interface Service {
  id: string;
  name: string;
  ownerTeam: string;
  description: string;
  tags: Record<string, string>;
}

export interface DeploymentTarget {
  id: string;
  serviceName: string;
  clusterName: string;
  provider: Provider;
  region: string;
  namespace: string;
  scalingStrategy: string;
  status: "HEALTHY" | "DEGRADED" | "DEPLOYING" | "FAILED";
  replicas: number;
  desiredReplicas: number;
  reliabilityScore: number;
  availabilityPct: number;
  latencyP95Ms: number;
  errorRatePct: number;
  monthlyCostUsd: number;
}

export interface Slo {
  id: string;
  targetLabel: string;
  sliType: string;
  objectiveValue: number;
  windowDays: number;
  currentValue: number;
  budgetRemainingPct: number;
  burnRate: number;
}

export interface ScalingEvent {
  id: string;
  targetLabel: string;
  previousReplicas: number;
  newReplicas: number;
  triggerMetric: string;
  triggerValue: number;
  strategy: string;
  decidedAt: string;
}

export interface HealingEvent {
  id: string;
  targetLabel: string;
  podName: string;
  reason: string;
  actionTaken: string;
  detectedAt: string;
  resolvedAt: string | null;
}

export interface Alert {
  id: string;
  targetLabel: string;
  severity: "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";
  status: "OPEN" | "ACKNOWLEDGED" | "RESOLVED";
  message: string;
  openedAt: string;
}

export interface ExperimentRun {
  id: string;
  serviceName: string;
  targetLabel: string;
  runType: string;
  faultType: string;
  status: string;
  scoreBefore: number;
  scoreDuring: number;
  scoreAfter: number;
  startedAt: string;
  endedAt: string | null;
}

export interface Policy {
  id: string;
  clusterName: string;
  maxReplicas: number;
  maxConcurrentExperiments: number;
  protectedNamespaces: string[];
}

export interface Overview {
  totalClusters: number;
  healthyClusters: number;
  totalServices: number;
  totalTargets: number;
  totalReplicas: number;
  openAlerts: number;
  avgScore: number;
  monthlyCostUsd: number;
  scoreByProvider: {
    provider: Provider;
    score: number;
    targets: number;
    costUsd: number;
  }[];
  scoreTrend: { date: string; score: number }[];
  recentScaling: ScalingEvent[];
  recentHealing: HealingEvent[];
  engineStatus: {
    name: string;
    status: string;
    detail: string;
    actionsLast24h: number;
  }[];
  observabilityFeed: {
    name: string;
    kind: string;
    status: string;
    ingestRate: string;
  }[];
}

/* --------------------------------- calls ---------------------------------- */

export const login = (email: string, password: string) =>
  request<LoginResponse>("/api/v1/auth/login", {
    method: "POST",
    body: JSON.stringify({ email, password }),
  });

export const me = (t: string) => authed<MeResponse>("/api/v1/auth/me", t);
export const getOverview = (t: string) => authed<Overview>("/api/v1/overview", t);
export const getClusters = (t: string) => authed<Cluster[]>("/api/v1/clusters", t);
export const getServices = (t: string) => authed<Service[]>("/api/v1/services", t);
export const getTargets = (t: string) => authed<DeploymentTarget[]>("/api/v1/targets", t);
export const getSlos = (t: string) => authed<Slo[]>("/api/v1/slos", t);
export const getPolicies = (t: string) => authed<Policy[]>("/api/v1/policies", t);
export const getAlerts = (t: string) => authed<Alert[]>("/api/v1/alerts", t);
export const getExperiments = (t: string) =>
  authed<ExperimentRun[]>("/api/v1/experiment-runs", t);
export const getScalingEvents = (t: string) =>
  authed<ScalingEvent[]>("/api/v1/control-plane/scaling-events", t);
export const getHealingEvents = (t: string) =>
  authed<HealingEvent[]>("/api/v1/control-plane/healing-events", t);

export const acknowledgeAlert = (t: string, id: string) =>
  authed<{ id: string; status: string }>(`/api/v1/alerts/${id}/acknowledge`, t, {
    method: "POST",
  });

export const resolveAlert = (t: string, id: string) =>
  authed<{ id: string; status: string }>(`/api/v1/alerts/${id}/resolve`, t, {
    method: "POST",
  });
