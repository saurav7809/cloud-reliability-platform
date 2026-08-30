# Phase 1 — Requirements

## 1. Product Summary

**AegisCloud** is a cloud-agnostic reliability platform that *deploys* services onto Kubernetes
clusters on any provider, *controls* them there (auto-scaling, self-healing, policy guardrails),
and *evaluates* them (SLOs, synthetic probes, chaos experiments) — producing comparable
reliability scorecards across providers, regions, or architectural strategies, without the core
platform ever depending on a specific cloud vendor's SDK.

"Cloud-agnostic" is achieved by talking to every cluster the same way: through the standard
Kubernetes API (`client-go`) against a registered cluster's kubeconfig. AWS EKS, Azure AKS, and
GCP GKE are distinguished only by which kubeconfig context is used and a `provider` label — the
Deployment Engine, Control Plane, and Evaluation Engine never branch on provider type.

## 2. Problem Statement

Teams running services across multiple clouds (or evaluating a migration between clouds) lack a
single, vendor-neutral way to answer: *"Is this service reliable, per the SLOs we set, and how
does it compare to the same service on a different provider/region/architecture?"* Each cloud's
native tooling (CloudWatch, Cloud Monitoring, Azure Monitor) reports differently, uses different
terminology, and can't produce an apples-to-apples comparison.

## 3. Goals (Phase 1 scope)

- Define the domain model, requirements, architecture, database schema, and API contract needed
  to start implementation in Phase 2.
- Keep the design provider-agnostic from day one — no AWS/GCP/Azure-specific types in the core
  domain or database schema.
- Design for incremental delivery: MVP must be usable with zero cloud credentials (synthetic
  HTTP/TCP probes only), with cloud metric ingestion added later without a schema rewrite.

## 4. Non-Goals (explicitly out of scope)

- Provisioning the underlying Kubernetes clusters themselves (EKS/AKS/GKE creation) — AegisCloud
  deploys *onto* clusters that already exist; it is not a cluster-provisioning tool (no Terraform
  equivalent in Phase 1).
- Full multi-tenant SaaS billing/subscription management.
- Mobile clients.
- Replacing the observability stack (Prometheus/Loki/OpenTelemetry) — AegisCloud runs and reads
  from these, it does not reimplement them.

## 5. Personas

| Persona | Needs |
|---|---|
| **SRE / Platform Engineer** | Define SLOs, see burn-rate, run chaos evaluations, compare providers |
| **Engineering Manager** | High-level reliability scorecards per service/team, trend over time |
| **Developer (service owner)** | See why their service's score dropped, what probe/SLO failed |
| **Admin** | Manage users, providers, integrations, retention policy |

## 6. Functional Requirements

### 6.1 Service, Cluster & Target Registration
- FR-1: User can register a **Service** (name, owner/team, description).
- FR-2: User can register a **Cluster** — `provider` (`AWS`, `GCP`, `AZURE`, `ON_PREM`, `OTHER`),
  `region`, and a kubeconfig reference. A local `kind` cluster registers exactly the same way as
  a real EKS/AKS/GKE cluster.
- FR-3: User can register one or more **Deployment Targets** — a `(Service, Cluster)` pair — each
  exposing one or more **Endpoints** (URL/host:port, protocol: HTTP, HTTPS, TCP, GRPC) once
  deployed.
- FR-4: Targets can be tagged (`env=prod`, `tier=critical`) for filtering and grouping.

### 6.2 Deployment Engine
- FR-5: User can deploy a service to a registered cluster; the Deployment Engine renders and
  applies standard Kubernetes objects (Deployment, Service, HPA) via `client-go`.
- FR-6: Deployment status (rollout progress, pod readiness) is surfaced back to the API/dashboard.
- FR-7: Redeploy and rollback of a target are supported.

### 6.3 Control Plane
- FR-8: **Auto-Scaling** — the Control Plane adjusts a target's replica count based on live
  metrics and a configurable scaling strategy (reuses the strategy-pattern approach from prior
  auto-scaling simulator work: CPU-based, latency-based, trend-based).
- FR-9: **Self-Healing** — the Control Plane detects unhealthy/crashed pods and restarts or
  reschedules them, recording a `HealingEvent`.
- FR-10: **Policy Engine** — every Auto-Scaling and Self-Healing action, and every Experiment
  Engine fault injection, is checked against configurable guardrails (max replicas, blast-radius
  limits, protected namespaces) before it executes; violations are rejected and logged.

### 6.4 Reliability Objectives
- FR-11: User can define **SLIs** (availability, latency-p95/p99, error-rate, throughput) per
  target.
- FR-12: User can define **SLOs** against each SLI (e.g. "99.9% availability over rolling 30d").
- FR-13: The system computes **error budget** consumption and **burn rate** per SLO automatically.

### 6.5 Evaluation Engine
- FR-14: System runs scheduled **synthetic probes** (HTTP/TCP/gRPC) against registered endpoints
  at a configurable interval and records latency, status, and success/failure.
- FR-15: System can ingest metrics from **Prometheus** (pull) and **Loki/OpenTelemetry** (push/
  receive), normalized into the same `MetricSample` model as probe results.
- FR-16: Every evaluation is recorded with start/end time, targets involved, and outcome.

### 6.6 Experiment Engine
- FR-17: User can trigger a **chaos/fault-injection experiment** (e.g. latency injection, pod
  kill, resource starvation) against a target, subject to Policy Engine limits.
- FR-18: The Experiment Engine captures before/during/after reliability metrics via the
  Evaluation Engine for every run.

### 6.7 Scoring & Comparison
- FR-19: System computes a normalized **Reliability Score (0–100)** per target per time window
  from SLO attainment, latency distribution, and incident count.
- FR-20: User can compare reliability scores **across targets** of the same service (e.g. AWS
  EKS us-east-1 vs GCP GKE us-central1) side by side.
- FR-21: System produces a historical trend (score over time) per target and per service.

### 6.8 Alerting
- FR-22: System raises an **alert** when SLO burn-rate exceeds a configurable threshold.
- FR-23: Alerts have a lifecycle: `OPEN → ACKNOWLEDGED → RESOLVED`.

### 6.9 Auditability
- FR-24: All mutating actions (create/update/delete on services, clusters, targets, SLOs, policy
  config) are written to an **audit log** with actor, timestamp, before/after state.

### 6.10 Access Control
- FR-25: Roles: `ADMIN` (manage everything), `OPERATOR` (deploy, run evaluations/experiments,
  edit SLOs), `VIEWER` (read-only).
- FR-26: Authentication via JWT; all API endpoints except login/health require a valid token.

## 7. Non-Functional Requirements

| Category | Requirement |
|---|---|
| **Portability** | Core domain/services must not import any cloud provider SDK. Provider-specific code is isolated behind an `Adapter` interface. |
| **Availability** | Platform itself should target 99.5% uptime (it is also a service being evaluated, per its own model — "dogfooding"). |
| **Performance** | Probe scheduling must support at least 500 concurrent endpoint checks without blocking the API. |
| **Data retention** | Raw metric samples retained 30 days by default (configurable); aggregated hourly rollups retained 13 months. |
| **Security** | Secrets (provider credentials, webhook URLs) encrypted at rest. All traffic over TLS. |
| **Extensibility** | Adding a new cloud provider = implementing one adapter interface, no schema migration required. |
| **Observability** | The platform emits its own OpenTelemetry traces/metrics (dogfooding again). |

## 8. Success Metrics (for the platform itself)

- Time to onboard a new service + first SLO: < 5 minutes.
- A new cloud provider adapter can be added without touching `core` or `database` modules.
- Reliability score recomputation for a target with 30 days of samples completes in < 2s.

## 9. Constraints & Assumptions

- Phase 1 assumes a single-tenant deployment (multi-org support deferred to a later phase; the
  schema reserves an `org_id` column so it isn't a breaking change later).
- Initial probe types are limited to HTTP/HTTPS/TCP/gRPC — no browser-based synthetic (Selenium)
  checks in MVP.
- Chaos evaluation execution (Phase 1 only *defines* the model) assumes probes are the mechanism
  to observe impact; actual fault injection tooling (e.g. Toxiproxy-style) is a Phase 3+ concern.

## 10. Phase Roadmap (for context)

| Phase | Name | Deliverable |
|---|---|---|
| **1** | Architecture | Requirements, architecture, database, APIs (this document set) |
| 2 | Platform Foundation | Go backend, React frontend, authentication, Docker, local Kubernetes (kind) |
| 3 | Deployment Engine | Manifest rendering + apply via client-go, cluster registration |
| 4 | Control Plane | Auto-Scaling, Self-Healing, Policy Engine, reconcile loop |
| 5 | Evaluation Engine | Probe scheduler, Prometheus/Loki/OTel ingestion, SLO + score computation |
| 6 | Experiment Engine | Chaos/fault-injection runs, before/during/after capture |
| 7 | Dashboard & Alerting | Full React dashboard, SSE streaming, burn-rate alerts |
| 8 | Multi-Cloud & Hardening | Real EKS/AKS/GKE clusters, multi-tenant readiness, prod deployment |
