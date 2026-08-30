# Phase 1 — Architecture & Requirements · Completion Checklist

Phase 1 has seven deliverables. This maps each to where it is satisfied, so the phase can be
signed off without reading all four documents end to end.

| # | Deliverable | Where | Status |
|---|---|---|---|
| 1 | Define Real-World Problem | [01-requirements.md §2](01-requirements.md) | ✅ |
| 2 | Define Users & Use Cases | [01-requirements.md §5](01-requirements.md) | ✅ |
| 3 | Define Functional Requirements | [01-requirements.md §6](01-requirements.md) | ✅ |
| 4 | Define Non-Functional Requirements | [01-requirements.md §7](01-requirements.md) | ✅ |
| 5 | Design System Architecture | [02-architecture.md](02-architecture.md) | ✅ |
| 6 | Design Database Schema | [03-database.md](03-database.md) | ✅ |
| 7 | Design API Architecture | [04-apis.md](04-apis.md) | ✅ |

---

## 1. Real-World Problem

Three compounding problems for teams running microservices across clouds:

1. **No common yardstick** — each cloud's native tooling reports differently and cannot produce
   an apples-to-apples reliability comparison.
2. **Symptoms are not causes** — in a dependency graph one slow service alerts every service that
   calls it, so teams page the wrong owner and debug the wrong system.
3. **Reliability work is reactive** — weaknesses are found by outages rather than by controlled
   experiments, and remediation stays manual even when the correct action is obvious.

## 2. Users & Use Cases

**5 personas:** SRE / Platform Engineer, Engineering Manager, Developer, On-call responder, Admin.

**8 use cases,** each with actor, trigger, flow, outcome and the requirements it exercises:

| ID | Use Case | Actor |
|---|---|---|
| UC-1 | Onboard a service and set a reliability target | SRE |
| UC-2 | Deploy a service to a cluster | SRE / Operator |
| UC-3 | Compare the same service across two clouds | Engineering Manager |
| **UC-4** | **Diagnose an incident instead of chasing alerts** ★ | On-call responder |
| UC-5 | Prove resilience before an outage does | SRE |
| UC-6 | Let the platform remediate unattended | SRE → platform |
| UC-7 | Cut cost without silently cutting reliability | Manager / Operator |
| UC-8 | Understand blast radius before a risky change | Developer |

UC-4 is the flagship: it is the reason the Intelligence Layer exists, and the clearest statement
of what separates this platform from a dashboard.

## 3. Functional Requirements

**45 requirements across 14 areas:**

| § | Area | FRs |
|---|---|---|
| 6.1 | Service, Cluster & Target Registration | FR-1 → 4 |
| 6.2 | Deployment Engine | FR-5 → 7 |
| 6.3 | Control Plane (Auto-Scaling, Self-Healing, Policy) | FR-8 → 10 |
| 6.4 | Reliability Objectives (SLI/SLO/error budget) | FR-11 → 13 |
| 6.5 | Evaluation Engine | FR-14 → 16 |
| 6.6 | Experiment Engine | FR-17 → 18 |
| 6.7 | Scoring & Comparison | FR-19 → 21 |
| 6.8 | Dependency & Failure Propagation | FR-22 → 26 |
| 6.9 | Root Cause Analysis | FR-27 → 30 |
| 6.10 | Optimization Recommendations | FR-31 → 34 |
| 6.11 | Autonomy | FR-35 → 38 |
| 6.12 | Alerting | FR-39 → 41 |
| 6.13 | Auditability | FR-42 → 43 |
| 6.14 | Access Control | FR-44 → 45 |

## 4. Non-Functional Requirements

Ten categories: portability, availability, performance, data retention, security, extensibility,
observability, **explainability**, **safety**, and **graph scale**.

The last three exist because of the autonomy claim:

- **Explainability** — no verdict, score or recommendation may be shown without its evidence. A
  number the user cannot interrogate is a liability, not a feature.
- **Safety** — autonomy defaults to `SUGGEST`; every autonomous action is policy-checked,
  reversible, audited, and rolled back if it does not help.
- **Graph scale** — ≥200 services and ≥1000 edges within interactive latency.

## 5. System Architecture

- Component diagram with the **Intelligence Layer** (Dependency & Propagation → RCA →
  Optimization Advisor) sitting between the engines and the Control Plane.
- The **autonomous loop**: observe → diagnose → decide → act → verify, with rollback and
  escalation when an action does not help.
- **Cloud-agnostic boundary**: every engine reaches clusters only through `client-go`; EKS/AKS/GKE
  and kind differ solely by kubeconfig and a label.
- Two worked request flows: deploy-then-evaluate, and diagnose-an-incident.
- Module breakdown (14 Go packages) with cloud-SDK exposure marked per module.
- 12 architecture decisions recorded with rationale.

**Load-bearing decision:** the Intelligence Layer reads telemetry but never writes to a cluster.
Only the policy-gated Control Plane acts. That is what keeps a wrong diagnosis from becoming an
unbounded action.

## 6. Database Schema

**23 tables**, PostgreSQL (SQLite for dev), `golang-migrate` from `0001_init.up.sql`.

| Group | Tables |
|---|---|
| Tenancy & identity | `organization`, `app_user`, `audit_log_entry` |
| Registry | `cluster`, `service`, `deployment_target`, `endpoint`, `policy` |
| Telemetry | `metric_sample`, `reliability_score_snapshot` |
| Objectives | `slo`, `error_budget_snapshot` |
| Control Plane | `scaling_event`, `healing_event` |
| Evaluation | `evaluation_run`, `evaluation_run_metric` |
| Alerting | `alert` |
| | *(23 total — see [03-database.md](03-database.md) for full column definitions)* |
| **Intelligence** | `service_dependency`, `incident`, `rca_verdict`, `recommendation` |
| **Autonomy** | `autonomy_setting`, `autonomous_action` |

Design constraints:
- No cloud-specific columns anywhere — provider is an enum label; the only cloud-specific field
  is a *reference* to a kubeconfig, never a credential.
- `org_id` reserved from day one, so multi-tenancy is additive rather than a rewrite.
- `service_dependency` is self-referential (both ends → `service`) — that is what makes the graph
  a graph.
- `incident` and `rca_verdict` are never purged early: they are the only evidence that the
  platform's diagnoses are correct.

## 7. API Architecture

REST over `/api/v1`, JWT bearer auth, RBAC on mutations, SSE for live streams, uniform error
envelope, URL-versioned.

| Group | Coverage |
|---|---|
| Auth | login, refresh, me |
| Clusters | CRUD + per-cluster policy |
| Services & Targets | CRUD, deploy, rollback, deployment status, endpoints |
| Control Plane | scaling events, healing events, live stream |
| SLOs | CRUD, error budget, budget history |
| Metrics | query, ingest, live stream |
| Evaluation & Experiments | list, start, abort, live stream |
| Scoring | current, history, cross-provider comparison |
| **Graph** | full graph, blast radius, dependencies, critical path, SPOF, manual edges |
| **Incidents & RCA** | list, detail, ranked verdicts with evidence, correctness feedback |
| **Recommendations** | list, detail, apply, dismiss |
| **Autonomy** | get/set levels, autonomous action history |
| Alerts, Audit, Users, System | feed + lifecycle, audit log, user admin, health/docs |

A subset is **implemented and live** as of Phase 2 — see `/swagger` on the running backend for the
OpenAPI 3.0 spec of what actually exists today.

---

## Scope Note

Phases 7–9 (dependency analysis, RCA, optimization) are the hardest part of this design, and RCA
especially is a genuinely difficult problem — correlation is not causation, and a confident wrong
answer is worse than no answer. The design responds to that in three ways rather than assuming it
away:

1. **Evidence is mandatory** (FR-29) — a verdict that cannot cite its reasoning is not shown.
2. **Accuracy is measured, not asserted** — precision@1 ≥ 70% against chaos experiments, which
   are the only incidents whose true cause is known in advance.
3. **Wrong answers are bounded** — the Intelligence Layer cannot act; the Control Plane can, only
   within policy, and rolls back what does not help.

This is also why the phase ordering puts chaos (Phase 6) before RCA (Phase 8): without ground
truth there is no honest way to know whether the RCA engine works.
