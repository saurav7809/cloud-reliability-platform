# Phase 1 — Architecture

## 1. Guiding Principle

> The core platform must never know which cloud a workload runs on.

Every provider-specific concern (talking to EKS vs AKS vs GKE, reading CloudWatch vs Cloud
Monitoring vs Azure Monitor) is pushed behind the Kubernetes API and a small **Cloud Adapter**
interface. Above Kubernetes, AegisCloud's engines only ever see standard K8s objects and a common
internal model — never an AWS/GCP/Azure-specific type.

## 2. System Architecture — AEGISCLOUD

```
                                     AEGISCLOUD
                                          │
                                ┌─────────┴─────────┐
                                │    Web Dashboard   │
                                └─────────┬─────────┘
                                          │
                                    API Gateway
                                          │
                ┌───────────────────────┼───────────────────────┐
                │                        │                        │
                ▼                        ▼                        ▼
          Deployment                Evaluation               Experiment
           Engine                     Engine                    Engine
                │                        │                        │
                └───────────────────────┼───────────────────────┘
                                          │
                        ┌─────────────────┴─────────────────┐
                        │        INTELLIGENCE LAYER          │
                        │                                     │
                        │   Dependency &      Root Cause      │
                        │   Propagation  ──►   Analysis       │
                        │      Graph              │           │
                        │                         ▼           │
                        │              Optimization Advisor   │
                        └─────────────────┬─────────────────┘
                                          │ diagnosis + intent
                                          ▼
                                    Control Plane
                                    (autonomy level:
                                  OBSERVE/SUGGEST/ACT)
                                          │
                 ┌───────────────────────┼───────────────────────┐
                 ▼                        ▼                        ▼
           Auto-Scaling               Self-Healing            Policy Engine
                 │                        │                        │
                 └───────────────────────┼───────────────────────┘
                                          │  every action gated + audited
                                          ▼
                                     Kubernetes
                                          │
                          ┌───────────────┼───────────────┐
                          ▼                ▼                ▼
                         AWS             Azure              GCP
                          │                │                 │
                         EKS              AKS               GKE

                                          ▲
                                          │ telemetry feeds evaluation,
                                          │ traces build the graph
                                   Observability
                                          │
                       ┌──────────────────┼──────────────────┐
                       ▼                  ▼                  ▼
                   Metrics              Logs               Traces
                  Prometheus            Loki           OpenTelemetry
```

### The autonomous loop

The Intelligence Layer is what makes the platform autonomous rather than merely automated. A
plain control loop reacts to a symptom — *CPU is high, add a replica*. AegisCloud closes a longer
loop that reacts to a **cause**:

```
observe ──► diagnose ──► decide ──► act ──► verify
   │           │            │          │        │
telemetry   RCA over    autonomy    Control  did the
+ probes    the graph    level +     Plane   score
            (cause,      Policy              recover?
             not          Engine                │
             symptom)                           │
   ▲                                            │
   └──────────── rollback + escalate ◄──────────┘
                 if it did not help
```

The verify-and-roll-back step is the part that makes autonomy defensible: an action that does not
improve the target within its window is reverted and handed to a human, so a confident-but-wrong
diagnosis degrades into a page rather than an outage.

## 3. Component Responsibilities

| Component | Responsibility |
|---|---|
| **Web Dashboard** | React SPA — register services, define SLOs, launch deployments/experiments, view scores, alerts, audit trail |
| **API Gateway** | Single entry point (Go HTTP service), auth (JWT), request routing to engines, rate limiting |
| **Deployment Engine** | Takes a service + target cluster, renders and applies Kubernetes manifests (Deployment/Service/HPA) — this is the only engine that *writes* workload state to a cluster |
| **Evaluation Engine** | Runs synthetic probes (HTTP/TCP/gRPC) and ingests metrics, computes SLO burn-rate/error budget and the Reliability Score |
| **Experiment Engine** | Orchestrates chaos/fault-injection runs (latency injection, pod kill, resource starvation) and captures before/during/after impact. Also supplies **ground truth for RCA**: it is the only source of incidents whose true cause is known in advance |
| **Dependency & Propagation** | Builds the service dependency graph from OpenTelemetry spans; computes blast radius, critical path and single points of failure; distinguishes propagated symptoms from the originating failure |
| **Root Cause Analysis** | Correlates graph position, temporal ordering, change events and resource saturation into a ranked, evidence-cited list of candidate causes |
| **Optimization Advisor** | Turns observed utilisation, cost and latency into cost/performance recommendations, each stating its reliability impact |
| **Control Plane** | Reconciliation loop that watches cluster + evaluation + diagnosis state and drives the three controllers below, at the configured autonomy level |
| **Auto-Scaling** | Adjusts replica counts based on live metrics and configured scaling strategy (reuses/extends the strategy pattern from the existing auto-scaling simulator work) |
| **Self-Healing** | Detects unhealthy pods/containers and restarts or reschedules them; records a `HealingEvent` |
| **Policy Engine** | Evaluates guardrails (max replicas, budget caps, blast-radius limits for experiments) before Auto-Scaling/Self-Healing/Experiment Engine act |
| **Kubernetes** | The only orchestration surface the Control Plane talks to — never a cloud SDK directly |
| **AWS EKS / Azure AKS / GCP GKE** | Concrete clusters registered as deployment targets; distinguished only by kubeconfig context + a `provider` label |
| **Observability** | Prometheus (metrics) + Loki (logs) + OpenTelemetry (traces), scraped/received from workloads and from AegisCloud's own services — feeds the Evaluation Engine and the dashboard |

## 4. Cloud-Agnostic Boundary

```
        Deployment Engine / Control Plane / Evaluation Engine / Experiment Engine
                                     │
                         client-go (Kubernetes API only)
                                     │
                              kubeconfig context
                                     │
                     ┌───────────────┼───────────────┐
                     ▼               ▼               ▼
                    EKS             AKS             GKE
```

- Every engine talks to Kubernetes through [`client-go`](https://github.com/kubernetes/client-go)
  against a `*rest.Config` built from a registered cluster's kubeconfig — nothing above that line
  imports `aws-sdk-go`, `azure-sdk-for-go`, or `google-cloud-go`.
- A registered **cluster** is just: `{ name, provider label, kubeconfig reference, region }`. The
  `provider` field is metadata for grouping/comparison in the dashboard — it never changes control
  flow.
- Where a provider-specific capability is unavoidable (e.g. reading CloudWatch cost data), it is
  isolated behind a small `CloudAdapter` interface, so it stays swappable and optional.
- The Intelligence Layer never touches a cluster at all. It reads telemetry and emits diagnoses;
  only the Control Plane acts. That separation is what keeps a wrong diagnosis from becoming an
  unbounded action.

## 5. Request Flow — Deploy, then Evaluate

```
User: "Deploy checkout-service to eks-prod, watch SLOs"
      │
      ▼
Deployment Engine
      │  render Deployment/Service/HPA manifests
      │  client-go apply → target cluster
      ▼
Control Plane (reconcile loop, e.g. every 5s)
      │
      ├──► Auto-Scaling   — reads live metrics, adjusts replicas within Policy Engine limits
      ├──► Self-Healing   — restarts/reschedules unhealthy pods, emits HealingEvent
      └──► Policy Engine  — guardrails checked before either acts
                     │
                     ▼
              Evaluation Engine
                     │  synthetic probes + Prometheus/Loki/OTel ingestion
                     ▼
              SLO burn-rate, error budget, Reliability Score
                     │
                     ▼
              Web Dashboard (SSE) + Alerting
```

## 5a. Request Flow — Diagnose an Incident

The case the whole Intelligence Layer exists for. Three services alert at once; only one is
actually broken.

```
auth-service latency 40ms → 900ms
      │
      ├──► checkout-service p95 breaches SLO   ← symptom
      ├──► catalog-service  p95 breaches SLO   ← symptom
      └──► auth-service     p95 breaches SLO   ← cause
                     │
                     ▼
            3 alerts would normally page 3 teams
                     │
                     ▼
          Dependency & Propagation Analysis
            graph: checkout → auth, catalog → auth
            auth has 0 failing dependencies
            checkout + catalog each depend on auth
                     │
                     ▼
              Root Cause Analysis
            correlates four signals:
              • graph position  — auth is upstream of both
              • temporal order  — auth degraded 45s first
              • change events   — auth deployed 6m ago
              • saturation      — auth CPU throttling
                     │
                     ▼
       Verdict: auth-service, confidence 0.91
       Evidence: [trace edges, first-degradation timestamps,
                  deployment id, throttling metric]
       Blast radius: checkout-service, catalog-service
                     │
                     ▼
       ┌─────────────┴─────────────┐
       ▼                            ▼
 ONE grouped incident       Control Plane
 (2 symptoms folded in)     autonomy = ACT?
                                    │
                              scale auth-service
                              (Policy Engine: within max replicas ✓)
                                    │
                                    ▼
                              verify: did p95 recover?
                              no → roll back + escalate
```

Note the honesty constraint from FR-29: the verdict ships with the evidence that produced it. If
the graph were incomplete — say catalog is uninstrumented — confidence drops and the verdict says
so, rather than quietly guessing.

Experiment runs follow the same loop, but the Experiment Engine first injects a fault (via the
Control Plane, subject to Policy Engine blast-radius limits) and tags the Evaluation Engine's
samples for that window as `BEFORE` / `DURING` / `AFTER`.

## 6. Module Breakdown (Go backend)

| Go module/package | Responsibility | Imports cloud/K8s SDKs? |
|---|---|---|
| `internal/domain` | Core types: Service, Cluster, Deployment, Slo, MetricSample, ExperimentRun, Alert | No |
| `internal/api` | HTTP handlers, routing, request/response DTOs | No |
| `internal/auth` | JWT issuance/validation, RBAC middleware | No |
| `internal/deployengine` | Manifest rendering + apply via client-go | **Yes — client-go only** |
| `internal/evalengine` | Probe scheduler, metric ingestion, SLO/score computation | No |
| `internal/experimentengine` | Fault-injection orchestration | **Yes — client-go only** |
| `internal/graph` | Dependency graph construction, blast radius, critical path, SPOF | No |
| `internal/rca` | Multi-signal correlation, ranked explainable verdicts | No |
| `internal/advisor` | Cost/performance recommendations with reliability impact | No |
| `internal/controlplane` | Reconcile loop coordinating the three controllers below | **Yes — client-go only** |
| `internal/controlplane/autoscaling` | Replica decision logic (strategy pattern) | No (reads metrics, writes via controlplane) |
| `internal/controlplane/selfhealing` | Unhealthy pod detection + remediation | No |
| `internal/controlplane/policy` | Guardrail evaluation | No |
| `internal/observability` | Prometheus/Loki/OTel client wiring | No (SDKs, not cloud-specific) |
| `internal/audit` | Write-only audit trail | No |
| `internal/store` | Postgres/SQLite persistence (repositories) | No |
| `web/` (React) | Dashboard UI | No |

## 7. Deployment View (Phase 1 design target)

```
┌───────────────────────────────────────────────────────────────┐
│                        Docker Compose (local dev)              │
│  ┌───────────────┐        ┌────────────────────────────────┐  │
│  │ React (Vite)   │  REST  │  Go API server                 │  │
│  │  :5173         │◄──────►│  :8080 — gateway + all engines │  │
│  └───────────────┘        │  talks to kind cluster via      │  │
│                            │  kubeconfig mounted read-only   │  │
│                            └───────────────┬──────────────────┘  │
│                                             │                    │
│                            ┌────────────────▼───────────────┐   │
│                            │ Postgres :5432 (SQLite in tests)│   │
│                            └──────────────────────────────────┘   │
└───────────────────────────────────────────┬─────────────────────┘
                                             ▼
                                   kind cluster ("aegiscloud-local")
                                   Prometheus + Loki + OTel Collector
```

- Local dev: `docker compose up` runs the API, Postgres, and frontend; a local `kind` cluster is
  the one and only "cloud" target until real EKS/AKS/GKE credentials are added.
- Production: same Go binary, containerized, deployed to any cluster; multiple real clusters
  (EKS/AKS/GKE) are registered the same way the local kind cluster is — just another kubeconfig.

## 8. Technology Stack

### Backend
- **Go 1.22+**, `net/http` + [`chi`](https://github.com/go-chi/chi) router
- `client-go` + `k8s.io/apimachinery` for all Kubernetes interaction
- `golang-jwt/jwt` for auth tokens, `bcrypt` for password hashing
- `pgx` / `database/sql` against PostgreSQL (dev: SQLite, zero external dependency)
- `prometheus/client_golang` + OpenTelemetry Go SDK (the platform's own observability)

### Frontend
- React + Vite + TypeScript
- Tailwind CSS
- SSE client for live evaluation/alert streaming

### Infrastructure
- Docker (multi-stage builds, distroless final image)
- `kind` for local Kubernetes; Helm chart for real-cluster install
- Prometheus + Loki + OpenTelemetry Collector (docker-compose services in local dev)

### Cross-cutting
- OpenAPI 3 (`swaggo/swag`) for API documentation
- `golang-migrate` for versioned DB migrations (schema-first, see [03-database.md](03-database.md))

## 9. Key Architectural Decisions (ADR summary)

| Decision | Rationale |
|---|---|
| Every engine talks to clusters only through `client-go` | Makes "cloud-agnostic" a structural guarantee — EKS/AKS/GKE differ only by kubeconfig |
| Policy Engine gates Auto-Scaling, Self-Healing, and Experiment Engine | One place to enforce guardrails instead of duplicating limit checks in three controllers |
| Intelligence Layer reads telemetry but never writes to clusters | A wrong diagnosis stays a wrong *opinion*; only the policy-gated Control Plane can act on it |
| Autonomy is a per-action, per-cluster setting defaulting to `SUGGEST` | "Autonomous" must be earned incrementally and revocably, not switched on globally at install |
| Autonomous actions must verify and roll back | Converts a confident-but-wrong diagnosis into a page instead of an outage |
| RCA verdicts must cite their evidence | An unexplainable confidence score cannot be trusted, debugged, or improved — FR-29 makes this structural |
| RCA accuracy is measured against chaos experiments | Injected faults are the only incidents whose true cause is known in advance, so Phase 6 must precede Phase 8 |
| Dependency graph is discovered from traces, not declared | A hand-maintained service map is stale within weeks; manual edges exist only as a labelled fallback |
| Local dev target is a real `kind` cluster, not a mock | Deployment/Control Plane code paths are exercised identically to production from day one |
| Go, single static binary, distroless image | Fast cold start, small attack surface, trivial to run in any cluster |
| `org_id` reserved in schema from day one | Avoids a breaking migration when multi-tenancy is added later |
| `golang-migrate` migrations from the first commit | Avoids ORM auto-migration drift once the schema stabilizes |
