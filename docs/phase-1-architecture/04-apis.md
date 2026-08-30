# Phase 1 — API Design

## 1. Conventions

- Base path: `/api/v1`
- Auth: `Authorization: Bearer <JWT>` on every endpoint except `POST /auth/login` and
  `GET /healthz`.
- Content type: `application/json` (SSE endpoints: `text/event-stream`).
- Errors: uniform envelope —
  ```json
  { "timestamp": "2026-08-30T10:00:00Z", "status": 404, "error": "NOT_FOUND",
    "message": "Target 3f2c... not found", "path": "/api/v1/targets/3f2c..." }
  ```
- Pagination: `?page=0&size=20` → responses wrapped as
  `{ "content": [...], "page": 0, "size": 20, "totalElements": 42 }`.
- IDs are UUIDs in the path (`metric_sample`/`evaluation_run_metric`/`error_budget_snapshot`/
  `reliability_score_snapshot`/`audit_log_entry` use synthetic bigint identity keys internally but
  are only ever returned embedded in parent resources, never addressed directly by ID).
- Versioning: URL-prefixed (`/api/v1`); breaking changes ship as `/api/v2` rather than mutating
  v1 responses.

## 2. Auth

| Method | Path | Description | Roles |
|---|---|---|---|
| POST | `/auth/login` | Exchange email/password for JWT | public |
| POST | `/auth/refresh` | Exchange refresh token for new access token | public (valid refresh token) |
| GET | `/auth/me` | Current user profile + role | any authenticated |

## 3. Clusters

| Method | Path | Description | Roles |
|---|---|---|---|
| GET | `/clusters` | List registered clusters | any |
| POST | `/clusters` | Register a cluster (local `kind` or real EKS/AKS/GKE) | ADMIN |
| GET | `/clusters/{clusterId}` | Cluster detail incl. connectivity status | any |
| PUT | `/clusters/{clusterId}` | Update cluster (region, kubeconfig_ref, active flag) | ADMIN |
| DELETE | `/clusters/{clusterId}` | Remove cluster | ADMIN |
| GET | `/clusters/{clusterId}/policy` | Get guardrail policy for a cluster | any |
| PUT | `/clusters/{clusterId}/policy` | Update guardrail policy (max_replicas, blast-radius, protected namespaces) | ADMIN |

**Example — register a cluster:**
```http
POST /api/v1/clusters
{
  "name": "aegiscloud-local",
  "providerType": "KIND",
  "kubeconfigRef": "local-kind-default"
}
```

## 4. Services & Deployment Targets (Registry + Deployment Engine)

| Method | Path | Description | Roles |
|---|---|---|---|
| GET | `/services` | List services (paginated, filter by tag) | any |
| POST | `/services` | Create service | ADMIN, OPERATOR |
| GET | `/services/{serviceId}` | Get service detail (includes target summaries) | any |
| PUT | `/services/{serviceId}` | Update service | ADMIN, OPERATOR |
| DELETE | `/services/{serviceId}` | Delete service (cascades targets) | ADMIN |
| GET | `/services/{serviceId}/targets` | List deployment targets for a service | any |
| POST | `/services/{serviceId}/targets` | Create a deployment target (service × cluster) | ADMIN, OPERATOR |
| GET | `/targets/{targetId}` | Target detail (cluster, namespace, status, endpoints, current score) | any |
| PUT | `/targets/{targetId}` | Update target (namespace, scaling_strategy, active flag) | ADMIN, OPERATOR |
| DELETE | `/targets/{targetId}` | Remove target | ADMIN |
| POST | `/targets/{targetId}/deploy` | Deployment Engine: render + apply manifests to the target's cluster | ADMIN, OPERATOR |
| POST | `/targets/{targetId}/rollback` | Roll back to the previous deployment | ADMIN, OPERATOR |
| GET | `/targets/{targetId}/deployment-status` | Rollout progress / pod readiness | any |
| POST | `/targets/{targetId}/endpoints` | Add an endpoint (protocol, address, interval) | ADMIN, OPERATOR |
| PUT | `/endpoints/{endpointId}` | Update endpoint config | ADMIN, OPERATOR |
| DELETE | `/endpoints/{endpointId}` | Remove endpoint | ADMIN |

**Example — create target:**
```http
POST /api/v1/services/{serviceId}/targets
{
  "clusterId": "c1a2...",
  "namespace": "checkout",
  "label": "prod-aws-use1",
  "scalingStrategy": "CPU"
}
```

## 5. Control Plane — Scaling & Healing Events

| Method | Path | Description | Roles |
|---|---|---|---|
| GET | `/targets/{targetId}/scaling-events` | Auto-Scaling decision history | any |
| GET | `/targets/{targetId}/healing-events` | Self-Healing action history | any |
| GET | `/control-plane/stream` | SSE stream of scaling/healing events as they happen | any |

## 6. SLOs & Error Budget

| Method | Path | Description | Roles |
|---|---|---|---|
| GET | `/targets/{targetId}/slos` | List SLOs for a target | any |
| POST | `/targets/{targetId}/slos` | Create an SLO | ADMIN, OPERATOR |
| PUT | `/slos/{sloId}` | Update SLO (objective, window) | ADMIN, OPERATOR |
| DELETE | `/slos/{sloId}` | Delete SLO | ADMIN |
| GET | `/slos/{sloId}/budget` | Latest error-budget snapshot (remaining %, burn rate) | any |
| GET | `/slos/{sloId}/budget/history` | Historical budget snapshots (chart data) | any |

**Example — create SLO:**
```http
POST /api/v1/targets/{targetId}/slos
{ "sliType": "AVAILABILITY", "objectiveValue": 99.9, "windowDays": 30 }
```

## 7. Metrics & Probes (Evaluation Engine)

| Method | Path | Description | Roles |
|---|---|---|---|
| GET | `/targets/{targetId}/metrics` | Query metric samples (`?type=&from=&to=`) | any |
| POST | `/metrics/ingest` | Push external metric samples (batch) | ADMIN, OPERATOR (or service token) |
| GET | `/targets/{targetId}/metrics/stream` | SSE stream of new samples as they land | any |

**Example — push metrics (for services without a supported adapter):**
```http
POST /api/v1/metrics/ingest
{
  "targetId": "8b3e...",
  "samples": [
    { "metricType": "LATENCY_MS", "value": 182.4, "sampledAt": "2026-08-30T10:00:00Z" }
  ]
}
```

## 8. Evaluation & Experiment Runs (incl. Chaos)

| Method | Path | Description | Roles |
|---|---|---|---|
| GET | `/services/{serviceId}/evaluation-runs` | List runs (filter by type/status) | any |
| POST | `/services/{serviceId}/evaluation-runs` | Start a manual or chaos evaluation run | ADMIN, OPERATOR |
| GET | `/evaluation-runs/{runId}` | Run detail incl. before/during/after metrics | any |
| POST | `/evaluation-runs/{runId}/abort` | Abort a running evaluation | ADMIN, OPERATOR |
| GET | `/evaluation-runs/stream` | SSE stream of run status changes | any |

**Example — start a chaos run:**
```http
POST /api/v1/services/{serviceId}/evaluation-runs
{
  "runType": "CHAOS",
  "targetId": "8b3e...",
  "faultSpec": { "type": "LATENCY_INJECTION", "magnitudeMs": 500, "durationSeconds": 120 }
}
```

## 9. Scoring & Comparison

| Method | Path | Description | Roles |
|---|---|---|---|
| GET | `/targets/{targetId}/score` | Current reliability score | any |
| GET | `/targets/{targetId}/score/history` | Score trend (`?from=&to=`) | any |
| GET | `/services/{serviceId}/score/compare` | Side-by-side score across all targets of a service | any |

**Example response — compare:**
```json
{
  "serviceId": "1a2b...",
  "targets": [
    { "targetId": "aws-1", "label": "prod-aws-use1", "score": 96.4 },
    { "targetId": "gcp-1", "label": "prod-gcp-usc1", "score": 91.2 }
  ]
}
```

## 10. Alerts

| Method | Path | Description | Roles |
|---|---|---|---|
| GET | `/alerts` | List alerts (filter by status/severity) | any |
| GET | `/alerts/stream` | SSE stream of new/updated alerts | any |
| POST | `/alerts/{alertId}/acknowledge` | Acknowledge an alert | ADMIN, OPERATOR |
| POST | `/alerts/{alertId}/resolve` | Resolve an alert | ADMIN, OPERATOR |

## 10a. Dependency Graph (Phase 7)

| Method | Path | Description | Roles |
|---|---|---|---|
| GET | `/graph` | Full service dependency graph (nodes + edges) | any |
| GET | `/graph/services/{serviceId}/blast-radius` | Transitive downstream services affected if this one degrades | any |
| GET | `/graph/services/{serviceId}/dependencies` | Direct dependencies of a service | any |
| GET | `/graph/critical-path` | Services on the critical path | any |
| GET | `/graph/spof` | Single points of failure, ranked by reach | any |
| POST | `/graph/edges` | Manually declare an edge (uninstrumented services) | ADMIN, OPERATOR |
| DELETE | `/graph/edges/{edgeId}` | Remove a manually declared edge | ADMIN |

**Example — blast radius:**
```json
{
  "serviceId": "svc-auth",
  "affected": [
    { "serviceId": "svc-checkout", "hops": 1, "dependencyStrength": 0.94 },
    { "serviceId": "svc-catalog",  "hops": 1, "dependencyStrength": 0.71 }
  ],
  "graphCompleteness": 0.82,
  "note": "1 service is uninstrumented; blast radius may be incomplete."
}
```

## 10b. Incidents & Root Cause Analysis (Phase 8)

| Method | Path | Description | Roles |
|---|---|---|---|
| GET | `/incidents` | List incidents (filter by status) | any |
| GET | `/incidents/{incidentId}` | Incident detail incl. grouped alerts and blast radius | any |
| GET | `/incidents/{incidentId}/rca` | Ranked candidate causes with evidence | any |
| POST | `/incidents/{incidentId}/rca/{verdictId}/feedback` | Mark a verdict correct/incorrect (feeds precision@1) | ADMIN, OPERATOR |
| POST | `/incidents/{incidentId}/resolve` | Resolve an incident | ADMIN, OPERATOR |
| GET | `/incidents/stream` | SSE stream of incident and diagnosis updates | any |

**Example — RCA response:**
```json
{
  "incidentId": "inc-42",
  "candidates": [
    {
      "verdictId": "v-1",
      "rank": 1,
      "target": "auth-service @ prod-eks-use1",
      "confidence": 0.91,
      "reasoning": "Degraded 45s before downstream services; both affected services depend on it; deployed 6m prior; CPU throttling observed.",
      "signalScores": {
        "graphPosition": 0.95, "temporalOrder": 0.92,
        "changeEvents": 0.88, "saturation": 0.79
      },
      "evidence": {
        "traceEdges": ["checkout->auth", "catalog->auth"],
        "firstDegradedAt": "2026-08-30T09:14:02Z",
        "deploymentId": "dep-8812",
        "metrics": ["container_cpu_cfs_throttled_seconds_total"]
      }
    }
  ],
  "blastRadius": ["checkout-service", "catalog-service"]
}
```

## 10c. Optimization Recommendations (Phase 9)

| Method | Path | Description | Roles |
|---|---|---|---|
| GET | `/recommendations` | List recommendations (filter by kind/status) | any |
| GET | `/recommendations/{recId}` | Detail incl. evidence and reliability impact | any |
| POST | `/recommendations/{recId}/apply` | Apply it (Policy Engine gated) | ADMIN, OPERATOR |
| POST | `/recommendations/{recId}/dismiss` | Dismiss it | ADMIN, OPERATOR |

**Example — recommendation:**
```json
{
  "id": "rec-7",
  "target": "catalog-service @ prod-aks-weu",
  "kind": "REDUCE_REPLICAS",
  "title": "Reduce replicas 5 → 3",
  "rationale": "p95 CPU utilisation 18% over 14 days; peak 34%.",
  "estimatedMonthlySavingUsd": 275.80,
  "reliabilityImpact": "LOW",
  "status": "OPEN",
  "pricingNote": "Directional — based on published list pricing, not negotiated rates."
}
```

## 10d. Autonomy (Phase 4+)

| Method | Path | Description | Roles |
|---|---|---|---|
| GET | `/autonomy` | Current autonomy level per cluster and action type | any |
| PUT | `/autonomy` | Set an autonomy level (`OBSERVE`/`SUGGEST`/`ACT`) | ADMIN |
| GET | `/autonomous-actions` | History of autonomous actions and their outcomes | any |
| GET | `/autonomous-actions/{actionId}` | Full record: observed → concluded → executed → verified | any |

## 11. Audit

| Method | Path | Description | Roles |
|---|---|---|---|
| GET | `/audit` | List audit entries (filter by entity/actor/date range) | ADMIN |

## 12. Users (Admin)

| Method | Path | Description | Roles |
|---|---|---|---|
| GET | `/users` | List users in org | ADMIN |
| POST | `/users` | Invite/create user | ADMIN |
| PUT | `/users/{userId}/role` | Change role | ADMIN |
| DELETE | `/users/{userId}` | Remove user | ADMIN |

## 13. System

| Method | Path | Description | Roles |
|---|---|---|---|
| GET | `/healthz` | Liveness/readiness | public |
| GET | `/swagger/index.html` | Interactive OpenAPI docs (swaggo) | any (or public in dev) |

## 14. Out of Scope for Phase 1 API Surface

These are named in requirements/architecture but their endpoints are deferred to the phase that
implements them, to avoid designing APIs against code that doesn't exist yet:
- Real cloud cost/metric adapter endpoints beyond Prometheus/Loki/OTel ingestion (Phase 8).
- Multi-org administration endpoints (post-MVP).
