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

/**
 * Creates a new organisation and signs its first administrator straight in.
 *
 * The account starts with nothing: tenant scoping is enforced in the API's own
 * queries, so a new organisation sees no cluster, service or incident belonging
 * to anyone else.
 */
export const signUp = (email: string, password: string, organisationName: string) =>
  request<LoginResponse>("/api/v1/auth/signup", {
    method: "POST",
    body: JSON.stringify({ email, password, organisationName }),
  });

/** Whether this deployment offers self-service registration at all. */
export const signupEnabled = () =>
  request<{ enabled: boolean }>("/api/v1/auth/signup/enabled").then((r) => r.enabled);
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

/* ------------------------------ live activity ------------------------------ */

/** One line of control-loop activity, as it arrives over the event stream. */
export interface LiveEvent {
  kind:
    | "connected"
    | "cycle-started"
    | "cycle-finished"
    | "decision"
    | "scaling"
    | "healing"
    | "outcome";
  at: string;
  text: string;
}

const LIVE_EVENTS = [
  "connected",
  "cycle-started",
  "cycle-finished",
  "decision",
  "scaling",
  "healing",
  "outcome",
] as const;

/** Renders one raw stream payload as the sentence the operator reads. */
function describe(kind: LiveEvent["kind"], data: Record<string, unknown>): string {
  switch (kind) {
    case "connected":
      return "connected to the control plane";
    case "cycle-started":
      return "reconciliation cycle started";
    case "cycle-finished":
      return `cycle finished: ${data.targetsExamined} examined, ${data.actionsApplied} applied, ${data.actionsSuggested} suggested, ${data.actionsRejected} rejected`;
    case "decision":
      return `${data.target} — ${data.decision}`;
    case "scaling":
      return `${data.target} scaled ${data.from} to ${data.to} on ${data.trigger}`;
    case "healing":
      return `${data.target} — ${data.action} ${data.pod} (${data.failure})`;
    case "outcome":
      return `${data.target} — ${data.actionType} verified: ${data.outcome} (${data.scoreBefore}% to ${data.scoreAfter}% ready)`;
  }
}

/**
 * Subscribes to the control plane's live event stream.
 *
 * <p>EventSource cannot send an Authorization header, so the token goes in the query
 * string — the one route on the API that accepts it that way. The browser reconnects
 * on its own if the connection drops, which is why nothing here retries.
 */
export function openControlPlaneStream(
  token: string,
  onEvent: (event: LiveEvent) => void,
): EventSource {
  const source = new EventSource(
    `${API_URL}/api/v1/control-plane/stream?token=${encodeURIComponent(token)}`,
  );

  for (const kind of LIVE_EVENTS) {
    source.addEventListener(kind, (message) => {
      const data = JSON.parse((message as MessageEvent).data) as Record<string, unknown>;
      onEvent({
        kind,
        at: typeof data.at === "string" ? data.at : new Date().toISOString(),
        text: describe(kind, data),
      });
    });
  }

  return source;
}

/* ------------------------------ microservices ------------------------------ */

/** A registered microservice, with what the cluster says about it right now. */
export interface Microservice {
  targetId: string;
  name: string;
  namespace: string;
  cluster: string;
  image: string;
  desiredReplicas: number;
  readyReplicas: number;
  health: "HEALTHY" | "DEGRADED" | "DOWN" | "MISSING";
  dependencies: string[];
}

export interface RegisterMicroservice {
  name: string;
  image: string;
  cluster: string;
  namespace?: string;
  replicas: number;
  containerPort: number;
  probePath?: string;
  ownerTeam?: string;
  scalingStrategy?: string;
  latencyObjectiveMs?: number;
  availabilityObjectivePct?: number;
  dependencies?: Record<string, string>;
}

/** Each step the registration took, so a partial one can be read rather than guessed at. */
export interface RegisterResult {
  serviceId: string;
  targetId: string | null;
  name: string;
  namespace: string;
  deployed: boolean;
  managed: boolean;
  measured: boolean;
  steps: string[];
  detail: string;
}

export interface DeploymentRecord {
  id: number;
  clusterName: string;
  namespace: string;
  workload: string;
  image: string;
  previousImage: string | null;
  replicas: number;
  actor: string;
  succeeded: boolean;
  detail: string;
  deployedAt: string;
}

export const getMicroservices = (t: string) =>
  authed<Microservice[]>("/api/v1/microservices", t);

export const registerMicroservice = (t: string, body: RegisterMicroservice) =>
  authed<RegisterResult>("/api/v1/microservices", t, {
    method: "POST",
    body: JSON.stringify(body),
  });

export const getDeploymentHistory = (t: string, workload?: string) =>
  authed<DeploymentRecord[]>(
    `/api/v1/deployments/history${workload ? `?workload=${encodeURIComponent(workload)}` : ""}`,
    t,
  );

export const rollbackDeployment = (
  t: string,
  cluster: string,
  namespace: string,
  workload: string,
) =>
  authed<{ workload: string; rolledBackTo: string; succeeded: boolean; detail: string }>(
    "/api/v1/deployments/rollback",
    t,
    { method: "POST", body: JSON.stringify({ cluster, namespace, workload }) },
  );

/* --------------------------------- builds ---------------------------------- */

export interface Build {
  id: string;
  serviceName: string | null;
  clusterName: string;
  gitUrl: string;
  gitRef: string;
  contextPath: string;
  image: string;
  status: "RUNNING" | "SUCCEEDED" | "FAILED";
  detail: string | null;
  startedAt: string;
  finishedAt: string | null;
}

export interface StartBuild {
  clusterName: string;
  gitUrl: string;
  gitRef?: string;
  contextPath?: string;
  dockerfile?: string;
  imageName: string;
  tag?: string;
}

export const getBuilds = (t: string) => authed<Build[]>("/api/v1/builds", t);

export const startBuild = (t: string, body: StartBuild) =>
  authed<{ buildId: string; image: string; jobName: string; detail: string }>(
    "/api/v1/builds",
    t,
    { method: "POST", body: JSON.stringify(body) },
  );

/** Whether this deployment has a registry to push built images to. */
export const getRegistry = (t: string) =>
  authed<{ configured: boolean; url: string; note: string }>("/api/v1/registry", t);
