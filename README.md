# AegisCloud

**A cloud-agnostic autonomous reliability platform for microservice applications.** It
continuously monitors distributed systems, performs controlled failure experiments, automatically
scales and heals services, analyzes service dependencies and failure propagation, performs
intelligent root-cause analysis, evaluates application resilience, and recommends cost and
performance optimizations across heterogeneous cloud environments.

Two words carry the design weight:

- **Autonomous** — the platform closes the loop itself: observe → diagnose → decide → act →
  verify, rolling back and escalating when an action does not help. Autonomy is a per-action,
  per-cluster setting that defaults to *suggest only*.
- **Microservice applications** — the unit of analysis is a *dependency graph*, not one isolated
  workload. That is what makes failure-propagation and root-cause analysis possible: a checkout
  failure caused by an auth timeout is only diagnosable if the platform knows checkout calls auth.

Cloud-agnosticism is structural, not aspirational: every engine reaches clusters only through the
standard Kubernetes API, so AWS EKS, Azure AKS, GCP GKE and a local kind cluster are
interchangeable targets rather than special cases.

Being built phase by phase. See [docs/phase-1-architecture](docs/phase-1-architecture) for the
foundational design.

## Phases

| Phase | Status |
|---|---|
| 1 — Architecture (requirements, architecture, database, APIs) | ✅ done |
| 2 — Platform Foundation (Spring Boot control plane, React dashboard, auth, Docker, kind, sample workloads) | ✅ done |
| 3 — Deployment Engine (fabric8 Kubernetes client, cluster registration, PostgreSQL) | ✅ done |
| 4 — Control Plane (Auto-Scaling, Self-Healing, Policy Engine, autonomy levels, live event stream) | ✅ done |
| 5 — Evaluation Engine (probes, telemetry ingestion, SLOs, scoring) | planned |
| 6 — Experiment Engine (chaos — also the ground truth for measuring RCA) | planned |
| 7 — Dependency & Propagation (service graph, blast radius, SPOF) | planned |
| 8 — Root Cause Analysis (multi-signal correlation, explainable verdicts) | planned |
| 9 — Optimization Advisor (cost + performance recommendations) | planned |
| 10 — Multi-Cloud & Hardening (real EKS/AKS/GKE, multi-tenancy, production) | planned |

Phases 7 and 8 are deliberately late: a dependency graph needs real telemetry flowing (Phase 5),
and RCA needs the chaos engine (Phase 6) to supply incidents whose true cause is known in advance
— otherwise there is no way to measure whether its verdicts are correct.

## Phase 1 Contents

```
docs/phase-1-architecture/
├── 00-phase-1-checklist.md — sign-off map: all 7 Phase 1 deliverables and where each is met
├── 01-requirements.md      — problem, 5 personas, 8 use cases, 45 FRs, 10 NFR categories
├── 02-architecture.md      — component diagram, Intelligence Layer, autonomous loop,
│                             cloud-agnostic boundary, module breakdown, 12 ADRs
├── 03-database.md          — 23 tables, ER overview, retention strategy
└── 04-apis.md              — full REST API contract
```

**Phase 1 deliverables** — all seven complete:

| # | Deliverable | Where |
|---|---|---|
| 1 | Define Real-World Problem | [§2](docs/phase-1-architecture/01-requirements.md) |
| 2 | Define Users & Use Cases | [§5](docs/phase-1-architecture/01-requirements.md) |
| 3 | Define Functional Requirements | [§6](docs/phase-1-architecture/01-requirements.md) |
| 4 | Define Non-Functional Requirements | [§7](docs/phase-1-architecture/01-requirements.md) |
| 5 | Design System Architecture | [02-architecture.md](docs/phase-1-architecture/02-architecture.md) |
| 6 | Design Database Schema | [03-database.md](docs/phase-1-architecture/03-database.md) |
| 7 | Design API Architecture | [04-apis.md](docs/phase-1-architecture/04-apis.md) |

## Repo Layout

```
control-plane/  Spring Boot 3.5 / Java 17 control plane — REST API, JWT auth via Spring
                Security, Flyway schema, the Deployment Engine (fabric8) and the
                autonomous control loop. This is the backend: Java throughout, with
                Python reserved for the AI/ML service (prediction, anomaly detection,
                AI-assisted RCA) where a model genuinely earns its place.
web/        React + Vite + TypeScript operator dashboard
workloads/  Deliberately failable sample service + manifests, so the platform has real
            pods to scale, heal and break (see workloads/README.md)
infra/
  kind/     kind-config.yaml — local Kubernetes cluster definition
  k8s/      base manifests (namespace, etc.)
docker-compose.yml   local dev: control plane on :8080, frontend dev server on :5173
```

## Running Locally

**Fastest path — Docker Compose** (needs only Docker):
```bash
docker compose up --build
```
- Backend: http://localhost:8080 (`GET /healthz`, `POST /api/v1/auth/login`)
- Frontend: http://localhost:5173
- Seeded login: `admin@aegiscloud.local` / `changeme123` (override via
  `AEGISCLOUD_ADMIN_EMAIL` / `AEGISCLOUD_ADMIN_PASSWORD` env vars)

**Control plane only, without Docker** (needs JDK 17 and Maven):
```bash
cd control-plane
DATABASE_URL='postgres://aegiscloud:aegiscloud@localhost:55432/aegiscloud?sslmode=disable' \
REDIS_ADDR=localhost:6379 \
mvn spring-boot:run
```
Note the port: `docker compose` publishes PostgreSQL on **55432**, not 5432, so a
PostgreSQL already installed on the host is left alone. Containers still reach it at
`postgres:5432`.

**Frontend only, without Docker** (needs Node — set `VITE_API_URL` if the backend isn't on
`localhost:8080`):
```bash
cd web
npm install
npm run dev
```

**Local Kubernetes** (needs [`kind`](https://kind.sigs.k8s.io/) — `winget install Kubernetes.kind`):
```bash
kind create cluster --config infra/kind/kind-config.yaml
kubectl apply -f infra/k8s/namespace.yaml
```
This cluster becomes AegisCloud's first registered `cluster` row once the Deployment Engine
(Phase 3) can register/target it — see [03-database.md](docs/phase-1-architecture/03-database.md).
It is registered exactly like a real EKS/AKS/GKE cluster, just with `provider_type = KIND`.

**Deploy the sample workloads** onto it, so there are real pods to scale, heal and break:
```bash
./workloads/deploy.sh
```
Three services, five pods, each able to crash, leak memory, inject latency or return errors on
demand — see [workloads/README.md](workloads/README.md).

## Dashboard

Six screens over the platform API, styled as a dark operator console:

| Screen | Shows |
|---|---|
| **Overview** | Fleet stat tiles, 14-day reliability trend, cross-cloud score comparison, control-plane engine status, observability sources |
| **Clusters** | Every registered cluster (EKS / AKS / GKE / kind) plus Policy Engine guardrails per cluster |
| **Services** | Service cards and the full deployment-target table — replicas, availability, p95, error rate, cost, score |
| **Control Plane** | Auto-Scaling decisions and Self-Healing actions with their triggers |
| **Reliability** | SLO attainment, error-budget burn-rate bars, chaos experiments with before/during/after impact |
| **Alerts** | Alert feed with working acknowledge/resolve actions (OPERATOR+ only) |

> **On the data:** the fleet is a seeded demo fleet served from the real API contract, so
> the UI has a realistic platform to render before persistence exists. It is **not** reading
> live cluster state — that begins in Phase 3 when the Deployment Engine connects via
> client-go. The dashboard says so on its Overview screen rather than implying live data.

## Verified So Far

### Phase 4 — Control Plane

Observed against the running stack (PostgreSQL 16 + Redis 7 in Compose, kind cluster
`aegiscloud-local` on Kubernetes v1.37.0, metrics-server installed):

**The loop**
- `POST /api/v1/control-plane/reconcile` runs the same method the scheduler runs; a
  cycle over the kind target reads live CPU utilisation from metrics-server
  (0.6% of the declared request) rather than any stored figure.
- 34 unit tests cover every scaling rule, the flapping guard and the whole failure
  taxonomy, with no cluster involved.

**Autonomy levels**
- At the default SUGGEST, a scale-down was decided, policy-checked and written to the
  ledger while the Deployment stayed at 3/3 replicas.
- Promoted to ACT through `PUT /control-plane/autonomy`, the loop scaled the same
  workload 3 → 2 unattended and recorded `scaling_event` plus an `autonomous_action`
  row with its trigger value.
- The immediately following cycle held: *"held: last scaled 1s ago, 178s of the 180s
  cooldown remain"*.

**Policy**
- With `aegiscloud` added to `protectedNamespaces`, the identical decision came back
  `REJECTED` with the reason recorded, and the cluster was not touched.

**Self-healing**
- A deliberately broken image (`ImagePullBackOff`) was classified
  `IMAGE_PULL_FAILURE` and **escalated, not restarted** — "a restart would fail
  identically" — with a `healing_event` row and no pod deletion.

**Verification**
- The applied scale-down was judged on a later cycle against the readiness it started
  from and closed as `NO_CHANGE` (100% → 100%), not left PENDING.

**Real time**
- `GET /api/v1/control-plane/stream` pushes `cycle-started`, `decision`, `scaling`,
  `healing`, `outcome` and `cycle-finished` as Server-Sent Events. Verified with a
  live subscription: events arrived as each decision was made, keep-alive comments hold
  the connection open, and the response carries
  `Access-Control-Allow-Origin: http://localhost:5173`. The dashboard's Control Plane
  page renders them in a Live Activity feed; `tsc -b` passes.

### Phase 3


Phase 3, observed against a running stack (PostgreSQL 16 + Redis 7 in Compose, kind
cluster `aegiscloud-local` on Kubernetes v1.37.0):

**Control plane**
- Flyway applies all 23 tables to an empty database on first boot; the seeder is
  idempotent and skips a populated one.
- All 10 read endpoints return 200 with row counts matching the seeded fleet.
  Aggregates check out by hand: 27 replicas, average score 92.9, $5,438.30/month.
- Login issues a JWT; wrong password, unknown user, missing token and malformed token
  all return 401 with the documented error envelope.
- RBAC enforced through `@PreAuthorize`: a VIEWER reads every endpoint (200) and is
  refused both alert mutations (403). ADMIN succeeds on both.
- Acknowledge/resolve persist and invalidate the cached rollup — `openAlerts` drops
  and `cacheHit` returns to false on the next read.
- A malformed alert id returns 404 rather than 500.

**Degradation**
- With Redis stopped, `/api/v1/overview` still returns 200 and `/healthz` reports
  `redis: disabled` while staying 200. A follow-up request completes in 82ms, so a
  dead cache is not being waited on.

**Deployment Engine (fabric8 7.8.0)**
- Probing `aegiscloud-local` reads 1/1 nodes ready and Kubernetes v1.37.0 live from
  the API, and writes both back to the cluster row.
- The three cloud clusters hold no kubeconfig on this machine and are reported
  `UNREACHABLE` with "no kubeconfig context configured" — not as healthy inventory.
- Deploying `aegiscloud/sample-service:v1` to the kind cluster through
  `POST /api/v1/deployments` rolled out 2/2 pods; re-sending the same request
  converges instead of duplicating containers.
- The engine refuses to modify a deployment it does not own unless `adopt=true`, and
  the foreign workload is left untouched when it does.

**Frontend**
- `tsc -b` passes against the Java API's response types.
- Login and every data fetch succeed cross-origin from `http://localhost:5173` with
  correct `Access-Control-Allow-Origin` headers.

**Application & Microservice Onboarding**, observed against a real public repository
(`GoogleCloudPlatform/microservices-demo`, 458 files, 12 polyglot services):

- `POST /api/v1/applications/{id}/repository` calls the real GitHub API, resolves
  the default branch, and records success/failure with a human-readable detail.
- `POST /api/v1/applications/{id}/discover` recursively scans the tree and correctly
  identified all 12 services with their real languages (Java, Go, Python, C#,
  JavaScript) and build tools, including a nested `src/cartservice/src` directory
  named after its parent rather than its literal path segment.
- Ports were read from the actual `EXPOSE` lines in each service's Dockerfile and
  matched the repository's real values (e.g. adservice 9555, productcatalogservice
  3550, shippingservice 50051) — nothing here is guessed or defaulted.
- Re-running discovery updated the 12 existing rows rather than duplicating them, and
  left the 3 hand-seeded services (checkout/catalog/auth) untouched, since discovery
  only overwrites rows it created itself.
- Resource validation rejects a limit set below its request (400, not a raw
  constraint-violation stack trace); a valid update persists and reads back.
- A secret-flagged environment variable is never stored in plaintext — `PUT` accepts
  a value but the row (and every subsequent read) returns `value: null` once
  `secret: true` is set.
- RBAC holds here too: VIEWER reads every onboarding endpoint (200) and is refused
  every write (403); ADMIN succeeds on both.

**Not yet verified:** the six dashboard screens have not been re-checked visually in a
browser against the Java backend — the HTTP layer beneath them is verified above, but
nobody has looked at the rendered pages since the port. The dashboard has no UI yet
for the onboarding endpoints above; they exist only as API surface yet.

## Known Environment Notes

- Docker Desktop on this machine installs to `%LOCALAPPDATA%\Programs\DockerDesktop\resources\bin`,
  which is not on `PATH` by default — add it, or `docker` won't resolve in a shell.
- The frontend build needs a bounded Node heap in some sandboxes:
  `NODE_OPTIONS=--max-old-space-size=1024 npm run build` (already set in `web/Dockerfile` and
  `docker-compose.yml`).
