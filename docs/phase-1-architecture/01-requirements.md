# Phase 1 — Requirements

## 1. Product Summary

**AegisCloud is a cloud-agnostic autonomous reliability platform for microservice applications.**
It continuously monitors distributed systems, performs controlled failure experiments,
automatically scales and heals services, analyzes service dependencies and failure propagation,
performs intelligent root-cause analysis, evaluates application resilience, and recommends cost
and performance optimizations across heterogeneous cloud environments.

Two words in that statement carry most of the design weight:

- **Autonomous** — the platform closes the loop on its own. It does not stop at showing a
  dashboard: it observes, diagnoses, decides, acts, and records what it did. Human approval is a
  *policy setting*, not a structural requirement. Every autonomous action passes the Policy
  Engine first and lands in the audit log.
- **Microservice applications** — the unit of analysis is a *dependency graph of services*, not
  one isolated workload. This is what makes failure-propagation analysis and root-cause analysis
  meaningful: a checkout failure caused by an auth timeout is only diagnosable if the platform
  knows checkout calls auth.

"Cloud-agnostic" is achieved by talking to every cluster the same way: through the standard
Kubernetes API (`client-go`) against a registered cluster's kubeconfig. AWS EKS, Azure AKS, and
GCP GKE are distinguished only by which kubeconfig context is used and a `provider` label — no
engine branches on provider type.

## 2. Problem Statement

Teams running microservices across multiple clouds face three compounding problems:

1. **No common yardstick.** Each cloud's native tooling (CloudWatch, Cloud Monitoring, Azure
   Monitor) reports differently and cannot produce an apples-to-apples reliability comparison.
2. **Symptoms are not causes.** In a dependency graph, one slow service produces alerts across
   every service that calls it. Teams page the wrong owner and debug the wrong system, because
   the tooling shows *where it hurts*, not *where it broke*.
3. **Reliability work is reactive.** Weaknesses are discovered by outages rather than by
   controlled experiments, and remediation is manual even when the correct action is obvious and
   repeatable.

AegisCloud addresses all three: a provider-neutral scoring model, a dependency-aware RCA engine
that distinguishes root cause from blast radius, and an autonomous control loop that remediates
within policy limits.

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
- **Instrumenting the user's services.** Dependency discovery reads OpenTelemetry spans the
  services already emit. AegisCloud does not inject tracing agents; uninstrumented services fall
  back to manually declared edges, with reduced RCA confidence stated plainly.
- **Autonomous action outside Kubernetes.** The platform scales, restarts and reschedules
  workloads. It does not modify cloud infrastructure, DNS, load balancers or databases.
- **RCA on signals it cannot see.** A cause outside the observed graph and telemetry (a bad
  third-party API, a corrupted record) will be reported as *undiagnosed*, never guessed at.

## 5. Users & Use Cases

### 5.1 Personas

| Persona | Needs |
|---|---|
| **SRE / Platform Engineer** | Define SLOs, see burn-rate, run chaos experiments, set autonomy levels, get a root cause instead of an alert storm |
| **Engineering Manager** | Reliability scorecards per service/team, trend over time, cost-saving recommendations with their reliability impact stated |
| **Developer (service owner)** | See why *their* service degraded — and whether the cause was actually a dependency they do not own |
| **On-call responder** | One diagnosed incident with a ranked cause and blast radius, not forty correlated pages |
| **Admin** | Manage users, clusters, integrations, retention, and how autonomous the platform is allowed to be |

### 5.2 Use Cases

Each use case names its actor, what triggers it, the flow, and the requirements it exercises.
UC-4 is the flagship — it is the reason the Intelligence Layer exists.

---

**UC-1 — Onboard a service and set a reliability target**
*Actor:* SRE · *Trigger:* a new microservice is ready for production

1. Registers the service (name, owner team, tags).
2. Registers or selects the cluster it will run on.
3. Creates a deployment target (service × cluster) and adds its endpoints.
4. Defines an SLO — "99.9% availability over rolling 30 days".
5. Platform begins probing and computing error budget within one interval.

*Outcome:* the service has a measurable reliability target. *Exercises:* FR-1→4, 11→14.
*Success criterion:* under 5 minutes end to end.

---

**UC-2 — Deploy a service to a cluster**
*Actor:* SRE / Operator · *Trigger:* a release is ready

1. Selects a target and triggers deploy.
2. Deployment Engine renders Deployment/Service/HPA manifests and applies them via client-go.
3. Rollout progress and pod readiness stream back to the dashboard.
4. If the rollout fails, the operator rolls back to the previous revision.

*Outcome:* workload running, deployment recorded as a change event RCA can later correlate
against. *Exercises:* FR-5→7.

---

**UC-3 — Compare the same service across two clouds**
*Actor:* Engineering Manager · *Trigger:* evaluating a migration, or justifying multi-cloud spend

1. Opens the service and views its targets side by side.
2. Compares reliability score, availability, p95 latency and monthly cost per provider.
3. Reviews the score trend to confirm the difference is sustained, not a blip.

*Outcome:* a provider decision backed by comparable numbers rather than vendor dashboards that
cannot be compared. *Exercises:* FR-19→21.

---

**UC-4 — Diagnose an incident instead of chasing alerts** ★
*Actor:* On-call responder · *Trigger:* three services breach their SLOs within a minute

1. Without AegisCloud: three alerts page three teams, each investigating a service that is
   working correctly, because the real fault is upstream.
2. Dependency analysis identifies that two of the three depend on the third.
3. RCA correlates four signals — graph position, temporal ordering (auth degraded 45s first), a
   deployment 6 minutes prior, and CPU throttling.
4. Responder opens **one** incident: cause `auth-service`, confidence 0.91, blast radius
   `checkout-service` + `catalog-service`, with the evidence cited.
5. The two downstream alerts are grouped under the cause rather than paging separately.
6. Responder marks the verdict correct, feeding the precision@1 metric.

*Outcome:* one team engaged on the actual fault, instead of three teams debugging symptoms.
*Exercises:* FR-22→30, 41.

---

**UC-5 — Prove resilience before an outage does**
*Actor:* SRE · *Trigger:* pre-launch resilience review

1. Selects a target and a fault (latency injection, pod kill, resource starvation).
2. Policy Engine validates blast radius; the run is rejected outright if it exceeds limits.
3. Experiment Engine injects the fault and captures before / during / after scores.
4. Report shows whether SLOs held, how far the failure propagated, and recovery time.

*Outcome:* a known weakness found deliberately, plus a labelled incident whose true cause is
known — the ground truth RCA accuracy is measured against. *Exercises:* FR-10, 17→18.

---

**UC-6 — Let the platform remediate unattended**
*Actor:* SRE (configuring) → platform (acting) · *Trigger:* traffic spike at 03:00

1. Admin sets autonomy for `SCALE_UP` on this cluster to `ACT`; everything else stays `SUGGEST`.
2. Platform observes CPU saturation, diagnoses insufficient capacity, checks the Policy Engine
   (within max replicas), and scales up.
3. It records observed → concluded → executed, then verifies whether the score recovered.
4. If the score does not improve within the window, the action is rolled back and a human is
   paged.

*Outcome:* routine, well-understood remediation happens without waking anyone; anything
uncertain still escalates. *Exercises:* FR-8, 10, 35→38, 43.

---

**UC-7 — Cut cost without silently cutting reliability**
*Actor:* Engineering Manager / Operator · *Trigger:* quarterly cost review

1. Opens recommendations, sorted by estimated saving.
2. Reads: "Reduce catalog-service 5 → 3 replicas — p95 CPU 18% over 14 days — save ~$276/mo —
   reliability impact: LOW."
3. Applies it; the Policy Engine gates the change and RBAC is enforced.
4. Platform records the outcome, so a recommendation that hurt reliability is visible afterwards
   rather than forgotten.

*Outcome:* savings taken with the reliability trade-off stated up front, and bad advice traceable
after the fact. *Exercises:* FR-31→34.

---

**UC-8 — Understand blast radius before a risky change**
*Actor:* Developer · *Trigger:* planning a breaking change to a shared service

1. Opens the service's blast radius view.
2. Sees every downstream service that would be affected, ranked by dependency strength, with
   graph completeness stated (e.g. "0.82 — 1 service uninstrumented").
3. Notifies the affected owners before shipping.

*Outcome:* the coordination cost of a change is known before it is made, not after.
*Exercises:* FR-22→25.

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

### 6.8 Dependency & Failure Propagation Analysis
- FR-22: System builds a **service dependency graph** — directed edges `A → B` meaning "A calls
  B" — discovered from OpenTelemetry trace spans, with manual declaration as a fallback for
  services that are not yet instrumented.
- FR-23: Each edge carries observed **call rate, error rate and latency**, so a weak dependency
  is visible before it fails.
- FR-24: System computes the **blast radius** of any service: the transitive set of upstream
  services that would be affected if it degraded, ranked by dependency strength.
- FR-25: System identifies **critical path** and **single points of failure** — services whose
  failure reaches a disproportionate share of the graph.
- FR-26: When multiple targets degrade together, the system correlates them against the graph to
  distinguish **propagated symptoms from the originating failure**.

### 6.9 Root Cause Analysis
- FR-27: On an incident (SLO breach, alert storm, or failed experiment), the RCA engine produces
  a **ranked list of candidate root causes**, each with a confidence score and the evidence
  supporting it.
- FR-28: RCA correlates across four signal classes: dependency-graph position (per 6.8), temporal
  ordering (what degraded first), deployment/change events, and resource saturation.
- FR-29: Every RCA verdict is **explainable** — it cites the specific metrics, spans, events and
  graph edges it used. An unexplainable verdict is not shown.
- FR-30: RCA output is retained per incident so its accuracy can be reviewed after the fact, and
  a human can mark a verdict correct or incorrect.

### 6.10 Optimization Recommendations
- FR-31: System recommends **cost optimizations** — over-provisioned replicas, oversized resource
  requests, workloads cheaper on a different registered provider — each with an estimated
  monthly saving.
- FR-32: System recommends **performance optimizations** — undersized resources causing
  throttling, scaling strategies mismatched to the observed traffic shape.
- FR-33: Every recommendation states its **reliability impact**, and a recommendation that would
  breach an existing SLO is never surfaced as safe.
- FR-34: A recommendation can be **applied** (subject to Policy Engine limits and RBAC) or
  dismissed, and the outcome is recorded so bad advice is visible.

### 6.11 Autonomy
- FR-35: Each remediation type has an **autonomy level**: `OBSERVE` (record only), `SUGGEST`
  (recommend to a human), or `ACT` (execute automatically within policy).
- FR-36: Autonomy level is configurable per cluster and per action type, and defaults to
  `SUGGEST` — the platform does not act unattended until explicitly permitted.
- FR-37: Every autonomous action records what was observed, what was concluded, what was done,
  and what happened next.
- FR-38: An autonomous action that fails to improve the situation within a configurable window is
  **rolled back** and escalated to a human.

### 6.12 Alerting
- FR-39: System raises an **alert** when SLO burn-rate exceeds a configurable threshold.
- FR-40: Alerts have a lifecycle: `OPEN → ACKNOWLEDGED → RESOLVED`.
- FR-41: Alerts caused by a diagnosed upstream failure are **grouped under that root cause**
  rather than paging separately, to suppress alert storms.

### 6.13 Auditability
- FR-42: All mutating actions (create/update/delete on services, clusters, targets, SLOs, policy
  config) are written to an **audit log** with actor, timestamp, before/after state.
- FR-43: Autonomous actions are audited identically to human ones, with the actor recorded as the
  engine that took them.

### 6.14 Access Control
- FR-44: Roles: `ADMIN` (manage everything, set autonomy levels), `OPERATOR` (deploy, run
  evaluations/experiments, edit SLOs, apply recommendations), `VIEWER` (read-only).
- FR-45: Authentication via JWT; all API endpoints except login/health require a valid token.

## 7. Non-Functional Requirements

| Category | Requirement |
|---|---|
| **Portability** | No engine may import a cloud provider SDK. Clusters are reached only through `client-go`. |
| **Availability** | Platform itself targets 99.5% uptime (it is also a service under its own model — "dogfooding"). |
| **Performance** | Probe scheduling supports ≥500 concurrent endpoint checks without blocking the API. |
| **Data retention** | Raw metric samples 30 days (configurable); aggregated rollups 13 months; incidents and RCA verdicts 13 months. |
| **Security** | Secrets (kubeconfigs, webhook URLs) encrypted at rest. All traffic over TLS. |
| **Extensibility** | A new provider is a new kubeconfig and a label — no schema migration, no engine change. |
| **Observability** | The platform emits its own OpenTelemetry traces/metrics. |
| **Explainability** | No RCA verdict, score or recommendation may be shown without the evidence behind it. A number the user cannot interrogate is a liability, not a feature. |
| **Safety** | Autonomy defaults to `SUGGEST`. Every autonomous action is policy-checked, reversible, audited, and rolled back if it does not help. |
| **Graph scale** | Dependency graph analysis handles ≥200 services and ≥1000 edges within interactive latency. |

## 8. Success Metrics (for the platform itself)

- Time to onboard a new service + first SLO: < 5 minutes.
- A new cluster on any provider is registered without a code change.
- Reliability score recomputation for a target with 30 days of samples: < 2s.
- Blast-radius computation on a 200-service graph: < 500ms.
- **RCA precision@1 ≥ 70% on injected faults** — measured by running known chaos experiments and
  checking whether the engine's top-ranked cause is the fault that was actually injected. This is
  the platform's central honesty check: chaos experiments provide ground truth that ordinary
  production incidents never do.
- Zero autonomous actions taken outside policy limits.

## 9. Constraints & Assumptions

- Phase 1 assumes a single-tenant deployment (multi-org deferred; schema reserves `org_id`).
- Probe types limited to HTTP/HTTPS/TCP/gRPC — no browser-based synthetic checks in MVP.
- **Dependency discovery requires trace instrumentation.** Services emitting OpenTelemetry spans
  are discovered automatically; the rest must be declared manually, and RCA confidence is reduced
  and labelled accordingly for those.
- **RCA quality is bounded by graph completeness.** An incomplete graph yields lower-confidence
  verdicts, and the engine reports that rather than compensating with a guess.
- Cost optimization uses published provider list pricing, not the user's negotiated rates, so
  savings estimates are directional. This is stated wherever a figure is shown.

## 10. Phase Roadmap

| Phase | Name | Deliverable |
|---|---|---|
| **1** | Architecture | Requirements, architecture, database, APIs (this document set) |
| **2** | Platform Foundation | Go backend, React dashboard, JWT auth, Docker, local kind cluster, failable sample workloads |
| 3 | Deployment Engine | Cluster registration, manifest rendering + apply via client-go, PostgreSQL persistence |
| 4 | Control Plane | Auto-Scaling, Self-Healing, Policy Engine, reconcile loop, autonomy levels |
| 5 | Evaluation Engine | Probe scheduler, Prometheus/Loki/OTel ingestion, SLO + error budget + score |
| 6 | Experiment Engine | Chaos/fault-injection runs with before/during/after capture — also the ground truth for measuring RCA |
| 7 | Dependency & Propagation | Service graph from trace spans, blast radius, critical path, SPOF detection |
| 8 | Root Cause Analysis | Multi-signal correlation, ranked explainable verdicts, alert grouping by cause |
| 9 | Optimization Advisor | Cost and performance recommendations with reliability impact and apply/dismiss tracking |
| 10 | Multi-Cloud & Hardening | Real EKS/AKS/GKE clusters, multi-tenant readiness, production deployment |

Phases 7 and 8 are deliberately late: a dependency graph needs real telemetry flowing (Phase 5)
and RCA needs the chaos engine (Phase 6) to supply ground truth for measuring whether its
verdicts are actually correct.
