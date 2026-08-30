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
                                    Control Plane
                                          │
                 ┌───────────────────────┼───────────────────────┐
                 ▼                        ▼                        ▼
           Auto-Scaling               Self-Healing            Policy Engine
                 │                        │                        │
                 └───────────────────────┼───────────────────────┘
                                          │
                                     Kubernetes
                                          │
                          ┌───────────────┼───────────────┐
                          ▼                ▼                ▼
                         AWS             Azure              GCP
                          │                │                 │
                         EKS              AKS               GKE

                                          ▲
                                          │
                                   Observability
                                          │
                       ┌──────────────────┼──────────────────┐
                       ▼                  ▼                  ▼
                   Metrics              Logs               Traces
                  Prometheus            Loki           OpenTelemetry
```

## 3. Component Responsibilities

| Component | Responsibility |
|---|---|
| **Web Dashboard** | React SPA — register services, define SLOs, launch deployments/experiments, view scores, alerts, audit trail |
| **API Gateway** | Single entry point (Go HTTP service), auth (JWT), request routing to engines, rate limiting |
| **Deployment Engine** | Takes a service + target cluster, renders and applies Kubernetes manifests (Deployment/Service/HPA) — this is the only engine that *writes* workload state to a cluster |
| **Evaluation Engine** | Runs synthetic probes (HTTP/TCP/gRPC) and ingests metrics, computes SLO burn-rate/error budget and the Reliability Score |
| **Experiment Engine** | Orchestrates chaos/fault-injection runs (latency injection, pod kill, resource starvation) and captures before/during/after impact via the Evaluation Engine |
| **Control Plane** | Reconciliation loop that watches cluster + evaluation state and drives the three controllers below toward the desired policy |
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
  isolated behind a small `CloudAdapter` interface, exactly as in the original CAREP design, so it
  stays swappable and optional.

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
| Local dev target is a real `kind` cluster, not a mock | Deployment/Control Plane code paths are exercised identically to production from day one |
| Go, single static binary, distroless image | Fast cold start, small attack surface, trivial to run in any cluster |
| `org_id` reserved in schema from day one | Avoids a breaking migration when multi-tenancy is added later |
| `golang-migrate` migrations from the first commit | Avoids ORM auto-migration drift once the schema stabilizes |
