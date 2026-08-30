# AegisCloud

A cloud-agnostic reliability platform: deploy services onto Kubernetes clusters on any provider,
control them there (auto-scaling, self-healing, policy guardrails), and evaluate them (SLOs,
synthetic probes, chaos experiments) — producing comparable reliability scorecards across
providers, regions, and architectures. Every engine talks to clusters only through the standard
Kubernetes API, so AWS EKS, Azure AKS, and GCP GKE are interchangeable targets, not special cases.

Being built phase by phase. See [docs/phase-1-architecture](docs/phase-1-architecture) for the
foundational design.

## Phases

| Phase | Status |
|---|---|
| 1 — Architecture (requirements, architecture, database, APIs) | ✅ done |
| 2 — Platform Foundation (Go backend, React frontend, auth, Docker, local K8s) | ⏳ backend + frontend + auth + Docker done; local `kind` cluster pending (needs `kind` installed) |
| 3 — Deployment Engine | planned |
| 4 — Control Plane (Auto-Scaling, Self-Healing, Policy Engine) | planned |
| 5 — Evaluation Engine | planned |
| 6 — Experiment Engine (chaos) | planned |
| 7 — Dashboard & Alerting | planned |
| 8 — Multi-Cloud & Hardening | planned |

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
backend/    Go API server (chi router, JWT auth) — Deployment/Control Plane/Evaluation/
            Experiment engines land in later phases
web/        React + Vite + TypeScript dashboard — login screen wired to the backend
infra/
  kind/     kind-config.yaml — local Kubernetes cluster definition (Phase 3 deploy target)
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

**Local Kubernetes** (needs [`kind`](https://kind.sigs.k8s.io/) installed — not yet included by
this project, since installing tools onto your machine wasn't done without asking first):
```bash
kind create cluster --config infra/kind/kind-config.yaml
kubectl apply -f infra/k8s/namespace.yaml
```
This cluster becomes AegisCloud's first registered `cluster` row once the Deployment Engine
(Phase 3) can register/target it — see [03-database.md](docs/phase-1-architecture/03-database.md).

## Verified So Far

- `go vet` and `go build` pass; the backend Docker image builds and runs.
- Full login flow tested end-to-end in a real browser against `docker compose up`: sign in →
  JWT issued → `/api/v1/auth/me` returns the authenticated admin profile → dashboard renders.
- The in-memory user store in `internal/auth/store.go` is a deliberate Phase 2 simplification —
  it will be replaced by the `app_user` table once persistence lands with the Deployment Engine.
