# Phase 1 — Database Design

## 1. Design Notes

- Relational (PostgreSQL prod / SQLite dev), managed via **`golang-migrate`** migrations from
  `0001_init.up.sql`.
- `org_id` is present on tenant-owned tables now (defaulted to a single fixed org for Phase 1–2)
  so multi-tenancy later is additive, not a migration rewrite.
- Raw `metric_sample` rows are the highest-volume table — indexed for time-range scans per
  target, and partition-ready (by `sampled_at`) if/when retention makes that necessary.
- No cloud-provider-specific columns anywhere in this schema — provider identity is just an enum
  value (`provider_type`) on `cluster`; the only cloud-specific field anywhere is a *reference* to
  a kubeconfig, never a raw credential, so adding a provider never adds a column.
- A local `kind` cluster and a real EKS/AKS/GKE cluster are rows in the same `cluster` table,
  distinguished only by `provider_type` — this is the schema-level expression of "cloud-agnostic."

## 2. Entity-Relationship Overview

```
 organization ──< app_user
      │
      ├──< cluster ──< policy
      │      │
      ├──< service ──< deployment_target >── cluster
      │                       │
      │                       ├──< endpoint ──< metric_sample
      │                       ├──< scaling_event
      │                       ├──< healing_event
      │                       ├──< slo ──< error_budget_snapshot
      │                       ├──< evaluation_run ──< evaluation_run_metric
      │                       ├──< reliability_score_snapshot
      │                       └──< alert
      │
      └──< audit_log_entry
```

## 3. Table Definitions

### `organization`
| Column | Type | Notes |
|---|---|---|
| id | UUID PK | |
| name | VARCHAR(120) | |
| created_at | TIMESTAMPTZ | |

### `app_user`
| Column | Type | Notes |
|---|---|---|
| id | UUID PK | |
| org_id | UUID FK → organization | |
| email | VARCHAR(255) UNIQUE | |
| password_hash | VARCHAR(255) | bcrypt |
| role | VARCHAR(20) | `ADMIN` \| `OPERATOR` \| `VIEWER` |
| created_at | TIMESTAMPTZ | |

### `service`
| Column | Type | Notes |
|---|---|---|
| id | UUID PK | |
| org_id | UUID FK → organization | |
| name | VARCHAR(120) | |
| description | TEXT | nullable |
| owner_team | VARCHAR(120) | nullable |
| tags | JSONB | `{"env":"prod","tier":"critical"}` |
| created_at | TIMESTAMPTZ | |
| updated_at | TIMESTAMPTZ | |

Unique constraint: `(org_id, name)`

### `cluster`
A registered Kubernetes cluster — local `kind` and real EKS/AKS/GKE clusters are the same row
shape.

| Column | Type | Notes |
|---|---|---|
| id | UUID PK | |
| org_id | UUID FK → organization | |
| name | VARCHAR(120) | e.g. `aegiscloud-local`, `prod-eks-use1` |
| provider_type | VARCHAR(20) | `AWS` \| `GCP` \| `AZURE` \| `ON_PREM` \| `KIND` \| `OTHER` |
| region | VARCHAR(60) | nullable (e.g. `us-east-1`, `null` for local/on-prem) |
| kubeconfig_ref | VARCHAR(255) | reference to a secret store entry — never a raw kubeconfig |
| is_active | BOOLEAN | default true |
| created_at | TIMESTAMPTZ | |

Unique constraint: `(org_id, name)`

### `policy`
Guardrails the Control Plane's Policy Engine checks before Auto-Scaling, Self-Healing, or the
Experiment Engine act.

| Column | Type | Notes |
|---|---|---|
| id | UUID PK | |
| cluster_id | UUID FK → cluster | nullable — null means org-wide default |
| max_replicas | INT | ceiling Auto-Scaling may not exceed |
| max_concurrent_experiments | INT | blast-radius limit for the Experiment Engine |
| protected_namespaces | JSONB | array of namespace names Self-Healing/Experiments must not touch |
| created_at | TIMESTAMPTZ | |
| updated_at | TIMESTAMPTZ | |

### `deployment_target`
A service deployed onto a specific cluster.

| Column | Type | Notes |
|---|---|---|
| id | UUID PK | |
| service_id | UUID FK → service | |
| cluster_id | UUID FK → cluster | |
| namespace | VARCHAR(120) | default `default` |
| label | VARCHAR(120) | human name, e.g. "prod-aws-use1" |
| scaling_strategy | VARCHAR(20) | `CPU` \| `LATENCY` \| `TREND` \| `NONE` |
| deployment_status | VARCHAR(20) | `PENDING` \| `DEPLOYING` \| `HEALTHY` \| `DEGRADED` \| `FAILED` |
| is_active | BOOLEAN | default true |
| created_at | TIMESTAMPTZ | |

Unique constraint: `(service_id, cluster_id, namespace)`

### `scaling_event`
Written by the Control Plane's Auto-Scaling controller each time it changes replica count.

| Column | Type | Notes |
|---|---|---|
| id | BIGINT PK (identity) | |
| target_id | UUID FK → deployment_target | |
| previous_replicas | INT | |
| new_replicas | INT | |
| trigger_metric | VARCHAR(20) | metric type that drove the decision |
| trigger_value | DOUBLE PRECISION | |
| decided_at | TIMESTAMPTZ | |

### `healing_event`
Written by the Control Plane's Self-Healing controller.

| Column | Type | Notes |
|---|---|---|
| id | BIGINT PK (identity) | |
| target_id | UUID FK → deployment_target | |
| pod_name | VARCHAR(255) | |
| reason | VARCHAR(50) | e.g. `CRASH_LOOP`, `NOT_READY`, `OOM_KILLED` |
| action_taken | VARCHAR(20) | `RESTARTED` \| `RESCHEDULED` \| `ESCALATED` |
| detected_at | TIMESTAMPTZ | |
| resolved_at | TIMESTAMPTZ | nullable |

### `endpoint`
A concrete checkable address under a target.

| Column | Type | Notes |
|---|---|---|
| id | UUID PK | |
| target_id | UUID FK → deployment_target | |
| protocol | VARCHAR(10) | `HTTP` \| `HTTPS` \| `TCP` \| `GRPC` |
| address | VARCHAR(500) | URL or host:port |
| probe_interval_seconds | INT | default 60 |
| timeout_ms | INT | default 5000 |
| expected_status_code | INT | nullable, HTTP only |
| is_active | BOOLEAN | default true |

### `metric_sample`
Normalized point-in-time measurement, from either a synthetic probe or a pulled provider metric.
Highest-volume table.

| Column | Type | Notes |
|---|---|---|
| id | BIGINT PK (identity) | |
| target_id | UUID FK → deployment_target | |
| endpoint_id | UUID FK → endpoint | nullable (null when the sample came from Prometheus/Loki/OTel, not a probe) |
| source | VARCHAR(20) | `PROBE` \| `PROMETHEUS` \| `OTEL` \| `PUSHED` |
| metric_type | VARCHAR(20) | `AVAILABILITY` \| `LATENCY_MS` \| `ERROR_RATE` \| `THROUGHPUT` |
| value | DOUBLE PRECISION | |
| success | BOOLEAN | nullable, probe results only |
| sampled_at | TIMESTAMPTZ | |

Indexes: `(target_id, metric_type, sampled_at DESC)`, `(sampled_at)` for retention sweeps.

### `slo`
| Column | Type | Notes |
|---|---|---|
| id | UUID PK | |
| target_id | UUID FK → deployment_target | |
| sli_type | VARCHAR(20) | `AVAILABILITY` \| `LATENCY_P95` \| `LATENCY_P99` \| `ERROR_RATE` \| `THROUGHPUT` |
| objective_value | DOUBLE PRECISION | e.g. `99.9` for availability, `300` for latency ms |
| window_days | INT | rolling window, e.g. `30` |
| created_at | TIMESTAMPTZ | |
| is_active | BOOLEAN | default true |

### `error_budget_snapshot`
Precomputed rollup so the dashboard doesn't recompute burn-rate from raw samples on every read.

| Column | Type | Notes |
|---|---|---|
| id | BIGINT PK (identity) | |
| slo_id | UUID FK → slo | |
| computed_at | TIMESTAMPTZ | |
| budget_remaining_pct | DOUBLE PRECISION | 0–100 |
| burn_rate | DOUBLE PRECISION | multiples of sustainable rate |

### `evaluation_run`
A discrete evaluation execution — a routine probe sweep (Evaluation Engine) or an explicit chaos
run (Experiment Engine). Both write to this same table so scoring has one code path.

| Column | Type | Notes |
|---|---|---|
| id | UUID PK | |
| service_id | UUID FK → service | |
| run_type | VARCHAR(20) | `SCHEDULED_PROBE` \| `CHAOS` \| `MANUAL` |
| triggered_by | UUID FK → app_user | nullable (null for scheduled) |
| fault_spec | JSONB | nullable, `CHAOS` runs only (fault type, magnitude, duration) — checked against `policy` before execution |
| started_at | TIMESTAMPTZ | |
| ended_at | TIMESTAMPTZ | nullable while running |
| status | VARCHAR(20) | `RUNNING` \| `COMPLETED` \| `FAILED` \| `ABORTED` \| `REJECTED_BY_POLICY` |

### `evaluation_run_metric`
Links an evaluation run to the metric samples it captured (before/during/after).

| Column | Type | Notes |
|---|---|---|
| id | BIGINT PK (identity) | |
| evaluation_run_id | UUID FK → evaluation_run | |
| metric_sample_id | BIGINT FK → metric_sample | |
| phase | VARCHAR(10) | `BEFORE` \| `DURING` \| `AFTER` |

### `reliability_score_snapshot`
| Column | Type | Notes |
|---|---|---|
| id | BIGINT PK (identity) | |
| target_id | UUID FK → deployment_target | |
| window_start | TIMESTAMPTZ | |
| window_end | TIMESTAMPTZ | |
| score | DOUBLE PRECISION | 0–100 |
| computed_at | TIMESTAMPTZ | |

Indexes: `(target_id, window_end DESC)` for trend queries and cross-target comparison.

### `alert`
| Column | Type | Notes |
|---|---|---|
| id | UUID PK | |
| target_id | UUID FK → deployment_target | |
| slo_id | UUID FK → slo | nullable |
| severity | VARCHAR(10) | `LOW` \| `MEDIUM` \| `HIGH` \| `CRITICAL` |
| status | VARCHAR(20) | `OPEN` \| `ACKNOWLEDGED` \| `RESOLVED` |
| message | TEXT | |
| opened_at | TIMESTAMPTZ | |
| acknowledged_at | TIMESTAMPTZ | nullable |
| acknowledged_by | UUID FK → app_user | nullable |
| resolved_at | TIMESTAMPTZ | nullable |

### `audit_log_entry`
| Column | Type | Notes |
|---|---|---|
| id | BIGINT PK (identity) | |
| org_id | UUID FK → organization | |
| actor_id | UUID FK → app_user | nullable (system actions) |
| action | VARCHAR(50) | e.g. `SLO_UPDATED`, `TARGET_CREATED` |
| entity_type | VARCHAR(50) | |
| entity_id | VARCHAR(64) | |
| before_state | JSONB | nullable |
| after_state | JSONB | nullable |
| created_at | TIMESTAMPTZ | |

## 4. Retention Strategy

- `metric_sample`: raw rows purged after 30 days (configurable); a nightly job rolls hourly
  aggregates into `reliability_score_snapshot`/`error_budget_snapshot` before purge so trend data
  survives indefinitely at reduced resolution.
- `audit_log_entry`: retained indefinitely (compliance).
- `evaluation_run` + `evaluation_run_metric`: retained 13 months.

## 5. Migration Convention

- `golang-migrate`, one up/down pair per change: `{n}_{description}.up.sql` /
  `{n}_{description}.down.sql` under `backend/db/migrations/`.
- No ORM auto-migration in any environment — schema changes only via migration files, so
  `0001_init.up.sql` created in Phase 2 is the literal source of truth for the tables above.
- SQLite is used for local/dev/test (zero external dependency); PostgreSQL-specific types
  (`JSONB`, `TIMESTAMPTZ`) fall back to `TEXT`/`DATETIME` equivalents there — the same migration
  files are written portably (see Phase 2 for the exact dialect handling).
