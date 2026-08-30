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
| 2 — Platform Foundation (Go backend, React dashboard, auth, Docker, kind, sample workloads) | ✅ done |
| 3 — Deployment Engine (client-go, cluster registration, PostgreSQL) | ⏳ next |
| 4 — Control Plane (Auto-Scaling, Self-Healing, Policy Engine, autonomy levels) | planned |
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
├── 01-requirements.md   — problem statement, personas, functional/non-functional requirements
├── 02-architecture.md   — AegisCloud component diagram, cloud-agnostic boundary, Go stack, ADRs
├── 03-database.md       — full schema, ER overview, retention strategy
└── 04-apis.md           — REST API contract for the MVP
```

## Repo Layout

```
backend/    Go API server (chi router, JWT auth, OpenAPI) — Deployment/Control Plane/
            Evaluation/Experiment engines land in later phases
web/        React + Vite + TypeScript operator dashboard
workloads/  Deliberately failable sample service + manifests, so the platform has real
            pods to scale, heal and break (see workloads/README.md)
infra/
  kind/     kind-config.yaml — local Kubernetes cluster definition
  k8s/      base manifests (namespace, etc.)
docker-compose.yml   local dev: backend on :8080, frontend dev server on :5173
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

**Backend only, without Docker** (needs Go 1.22+):
```bash
cd backend
go run ./cmd/server
```

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

- `go vet` and `go build` pass; the backend Docker image builds and runs.
- Full login flow tested end-to-end in a real browser against `docker compose up`: sign in →
  JWT issued → `/api/v1/auth/me` returns the authenticated admin profile → dashboard renders.
  Wrong password and missing token both correctly return 401.
- `kind` cluster `aegiscloud-local` created and healthy (control-plane node `Ready`,
  Kubernetes v1.37.0), with the `aegiscloud` namespace applied.
- All six dashboard screens rendered and clicked through in a browser with no console
  errors; acknowledging an alert round-trips through the API and updates the badge count.
- The in-memory user store in `internal/auth/store.go` is a deliberate Phase 2 simplification —
  it will be replaced by the `app_user` table once persistence lands with the Deployment Engine.

## Known Environment Notes

- Docker Desktop on this machine installs to `%LOCALAPPDATA%\Programs\DockerDesktop\resources\bin`,
  which is not on `PATH` by default — add it, or `docker` won't resolve in a shell.
- The frontend build needs a bounded Node heap in some sandboxes:
  `NODE_OPTIONS=--max-old-space-size=1024 npm run build` (already set in `web/Dockerfile` and
  `docker-compose.yml`).
