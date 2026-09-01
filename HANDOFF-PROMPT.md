# AegisCloud — Continuation Prompt

You are continuing work on **AegisCloud**, an autonomous multi-cloud reliability platform.
Open the folder `C:\Users\gaura\.gemini\antigravity-ide\scratch\aegiscloud` as your working directory.

---

## 0. Operating rules — follow these for every phase, without exception

1. **Read before you write.** Inspect the existing code and docs before adding anything. Match the
   existing style, naming, comment density and structure. Do not reinvent what already exists.
2. **No placeholders, no TODOs, no stubs, no mock data** in delivered code. If something cannot be
   built for real, say so instead of faking it.
3. **Every feature must be tested.** Write tests, run them, and show the output.
4. **Every feature must be run.** Start the application, exercise the endpoint or screen, and
   confirm the actual behaviour. "It compiles" is not "it works".
5. **Fix all errors before moving on.** Do not advance to the next task, file, or phase while
   anything is failing.
6. **Report honestly.** Never claim something is done, tested, or working unless you have actually
   observed it. If you are blocked, stop and say exactly what is blocking you and why. Partial
   progress reported as complete is the single worst failure mode here.
7. **One phase at a time, in order.** Finish and verify a phase completely before starting the next.
8. **Explain design decisions** in comments where the reasoning is not obvious from the code — the
   existing codebase does this, keep it up.
9. **Ask before deleting or overwriting** anything you did not create.
10. **Keep the frontend contract stable.** `web/` is working. JSON field names must not change
    unless you also update `web/` and re-verify the UI in a browser.

---

## 1. Where the project stands

**Repo layout**

```
aegiscloud/
├── README.md                 # phase table, repo layout, verified-so-far notes
├── docker-compose.yml        # postgres:16 + redis:7 + backend + web(vite)
├── docs/phase-1-architecture/
│   ├── 00-phase-1-checklist.md   # 7 deliverables, all done
│   ├── 01-requirements.md        # problem, 5 personas, 8 use cases, 45 FRs, 10 NFR categories
│   ├── 02-architecture.md        # components, Intelligence Layer, autonomous loop, 12 ADRs
│   ├── 03-database.md            # full 23-table schema
│   └── 04-apis.md                # REST /api/v1 design
├── backend/                  # Go 1.22 — Phase 2, WORKING
├── control-plane/            # Spring Boot port — Phase 3, IN PROGRESS (see section 2)
├── web/                      # React + Vite + TypeScript — Phase 2, WORKING
├── infra/kind/kind-config.yaml
├── infra/k8s/namespace.yaml
└── workloads/sample-service/ # deliberately failable service + manifests
```

**Phase status**

| Phase | Scope | Status |
|---|---|---|
| 1 | Architecture, requirements, database design, API design | done |
| 2 | Platform foundation: Go backend, React dashboard, auth, Docker, kind, workloads | done |
| 3 | Deployment Engine: client-go/fabric8, cluster registration, PostgreSQL persistence | **in progress** |
| 4 | Control Plane: auto-scaling, self-healing, policy engine, autonomy levels | not started |
| 5 | Evaluation Engine: probes, telemetry ingestion, SLOs, scoring | not started |
| 6 | Experiment Engine: chaos (also the ground truth for measuring RCA) | not started |
| 7 | Dependency & Propagation: service graph, blast radius, SPOF | not started |
| 8 | Root Cause Analysis: multi-signal correlation, explainable verdicts | not started |
| 9 | Optimization Advisor: cost + performance recommendations | not started |
| 10 | Multi-Cloud & Hardening: real EKS/AKS/GKE, multi-tenancy, production | not started |
| 11–15 | **Not yet defined — see section 5** | needs definition |

**What the Go backend already does** (`backend/`, ~1,900 lines). This is your reference
implementation — the Spring Boot port must reproduce its behaviour exactly.

| File | Lines | Responsibility |
|---|---|---|
| `cmd/server/main.go` | 75 | startup, migrate, seed, graceful shutdown |
| `internal/api/router.go` | 100 | chi router, CORS, route table, RBAC grouping |
| `internal/api/handlers_auth.go` | 67 | login, me |
| `internal/api/handlers_platform.go` | 96 | the 10 read endpoints + 2 alert mutations |
| `internal/api/handlers_health.go` | 61 | `/healthz` with pg + redis probes |
| `internal/api/handlers_index.go` | 157 | HTML index page |
| `internal/api/handlers_swagger.go` | 78 | `/openapi.yaml`, `/swagger` |
| `internal/auth/jwt.go` | 51 | HS256 issue/parse, 24h TTL |
| `internal/auth/middleware.go` | 61 | bearer auth + `RequireRole` |
| `internal/auth/store.go` | 65 | **in-memory** user store (Phase 2 simplification) |
| `internal/cache/cache.go` | 101 | Redis, **optional by design** — degrades, never fails |
| `internal/db/db.go` | 108 | pgxpool + golang-migrate |
| `internal/db/seed.go` | 343 | demo data seeding |
| `internal/domain/models.go` | 249 | the DTO contract `web/` consumes |
| `internal/store/store.go` | 391 | all SQL queries |

**Live endpoints** (`/api/v1`, JWT bearer, from `router.go`):

```
POST /auth/login                         public
GET  /auth/me                            authenticated
GET  /overview
GET  /clusters
GET  /services
GET  /targets
GET  /slos
GET  /policies
GET  /control-plane/scaling-events
GET  /control-plane/healing-events
GET  /experiment-runs
GET  /alerts
POST /alerts/{alertId}/acknowledge       ADMIN or OPERATOR only
POST /alerts/{alertId}/resolve           ADMIN or OPERATOR only
GET  /                                   public HTML index
GET  /healthz                            public
GET  /openapi.yaml, /swagger             public in dev
```

**Environment variables** — keep these names identical in the Java port:

```
DATABASE_URL                postgres://aegiscloud:aegiscloud@localhost:5432/aegiscloud?sslmode=disable
REDIS_ADDR                  localhost:6379          (empty = cache disabled, must still serve)
PORT                        8080
AEGISCLOUD_JWT_SECRET       dev-secret-change-me
AEGISCLOUD_WEB_ORIGIN       http://localhost:5173   (comma-separated list)
AEGISCLOUD_ADMIN_EMAIL      admin@aegiscloud.local
AEGISCLOUD_ADMIN_PASSWORD   changeme123
```

---

## 2. Phase 3 — exactly what exists and what is left

The decision made was to **port the Go backend to Spring Boot** (Java 17, Spring Boot 3.5.3) as
`control-plane/`, then build the Deployment Engine on top of it using **fabric8 kubernetes-client**
as the Java equivalent of `client-go`. The Go backend stays in the repo untouched until the port is
verified working, then the README phase table is updated.

**Already written — 3 files, never successfully compiled:**

- `control-plane/pom.xml` — Spring Boot 3.5.3 parent, Java 17. Dependencies: web, validation,
  actuator, jdbc, postgresql, flyway-core + flyway-database-postgresql, data-redis,
  spring-security-crypto (bcrypt only — deliberately *not* the full security starter, because the
  port uses one explicit JWT filter mirroring the Go middleware rather than a filter chain whose
  defaults have to be fought), jjwt 0.12.6, springdoc-openapi-starter-webmvc-ui 2.8.9,
  fabric8 kubernetes-client 6.13.4, starter-test.
- `control-plane/src/main/resources/db/migration/V1__init.sql` — the full 23-table schema, copied
  **verbatim** from the Go `0001_init.up.sql` so there is zero transcription risk.
- `control-plane/src/main/java/io/aegiscloud/controlplane/domain/Models.java` — the DTO contract,
  with JSON field names identical to the Go structs so `web/` needs no change.

**Known caveat:** dependency resolution was interrupted partway through by a full disk and has never
completed. **Your first action must be to run `mvn -q dependency:go-offline` (or a plain
`mvn compile`) and fix whatever surfaces.** Do not assume the pom is correct — verify it.

**Remaining Phase 3 work:**

1. **Verify the build.** Resolve dependencies, compile, fix any version conflicts.
2. **Port config and startup** — `application.yml` reading the env vars above,
   `ControlPlaneApplication`, Flyway on startup, graceful shutdown.
3. **Port auth** — JWT issue/parse (HS256, 24h), a `OncePerRequestFilter` bearer filter, role check
   equivalent to `RequireRole(ADMIN, OPERATOR)`. Replace the Go in-memory user store with the real
   `app_user` table — this replacement is explicitly called out in `README.md` as Phase 3 work.
4. **Port the cache** — Redis wrapper that **degrades silently when Redis is absent**. This is a
   design requirement, not an optimisation: the platform must serve with the cache down.
5. **Port the store** — all queries from `store.go` (391 lines) using `JdbcTemplate`.
6. **Port the handlers** — all 16 routes above, with CORS and the uniform error envelope.
7. **Port the seed** — `seed.go` (343 lines) demo data, plus admin user bootstrap.
8. **Build the Deployment Engine** (the actual new Phase 3 feature), using fabric8:
   - cluster registration against a real kubeconfig (`provider_type = KIND` first)
   - connectivity test and cluster health read
   - deploy a service to a target, rollback, deployment status
   - endpoint discovery
   - the corresponding endpoints from `docs/phase-1-architecture/04-apis.md`
   - **Architectural constraint from the ADRs:** every engine reaches clusters *only* through the
     Kubernetes client. No cloud-specific SDK calls anywhere. EKS/AKS/GKE and kind must differ
     solely by kubeconfig and a label.
9. **Wire `docker-compose.yml`** to build `control-plane/` instead of `backend/`.
10. **Update `README.md`** — phase table, repo layout, "Verified So Far".

**Phase 3 is done only when:** `docker compose up` brings up postgres + redis + the Spring Boot
control plane + the web dashboard; you log into the dashboard in a browser as
`admin@aegiscloud.local` / `changeme123`; all six pages (Overview, Clusters, Services, Reliability,
ControlPlane, Alerts) render real data served by the Java backend; a kind cluster is registered
through the API and a sample workload from `workloads/` is deployed to it and visible in the UI.
Show the evidence for each.

---

## 3. How to bring the environment up

```bash
docker compose up -d postgres redis
```

```bash
cd control-plane && mvn spring-boot:run
```

```bash
cd web && npm install && npm run dev
```

Dashboard at http://localhost:5173, API at http://localhost:8080.

kind cluster for the Deployment Engine:

```bash
kind create cluster --config infra/kind/kind-config.yaml
```

```bash
kubectl apply -f infra/k8s/namespace.yaml && kubectl apply -f workloads/k8s/
```

**Environment note:** this machine recently ran completely out of disk, which is what stalled the
first attempt at the port. Check free space before large operations — `docker system prune`
reclaims a lot — and if a build fails with `FileSystemException ... not enough space on the disk`,
stop and report it rather than retrying.

---

## 4. Phases 4–10

Build these in order, only after Phase 3 is verified. Each is specified in
`docs/phase-1-architecture/`: read `01-requirements.md` for the FRs each phase satisfies,
`02-architecture.md` for the module boundaries, `03-database.md` for the tables involved, and
`04-apis.md` for the endpoints to implement.

- **Phase 4 — Control Plane.** FR-8 to FR-10, FR-35 to FR-38. Auto-scaling, self-healing, policy
  engine, autonomy levels. **Autonomy defaults to `SUGGEST`.** Every autonomous action must be
  policy-checked, reversible, audited, and rolled back if it does not help. Tables:
  `scaling_event`, `healing_event`, `policy`, `autonomy_setting`, `autonomous_action`.
- **Phase 5 — Evaluation Engine.** FR-11 to FR-16, FR-19 to FR-21. Probes, telemetry ingestion,
  SLI/SLO evaluation, error budgets, reliability scoring, cross-provider comparison. Tables:
  `metric_sample`, `slo`, `error_budget_snapshot`, `evaluation_run`, `evaluation_run_metric`,
  `reliability_score_snapshot`.
- **Phase 6 — Experiment Engine.** FR-17 to FR-18. Chaos injection. This is scheduled before RCA on
  purpose: chaos experiments are the only incidents whose true cause is known in advance, so they
  are the ground truth for measuring whether Phase 8 actually works.
- **Phase 7 — Dependency and Propagation.** FR-22 to FR-26. Service graph, blast radius, critical
  path, SPOF detection, manual edges. `service_dependency` is self-referential — that is what makes
  the graph a graph. Must handle at least 200 services and 1000 edges within interactive latency.
- **Phase 8 — Root Cause Analysis.** FR-27 to FR-30. Multi-signal correlation producing ranked
  verdicts. **Hard requirements:** a verdict that cannot cite its evidence must not be shown
  (FR-29); accuracy must be *measured* against Phase 6 chaos experiments, target precision@1 at
  least 70%; the Intelligence Layer reads telemetry but **never writes to a cluster** — only the
  policy-gated Control Plane acts. That separation is what keeps a wrong diagnosis from becoming an
  unbounded action. Tables: `incident`, `rca_verdict`.
- **Phase 9 — Optimization Advisor.** FR-31 to FR-34. Cost and performance recommendations with
  apply and dismiss. Must never silently trade reliability for cost (UC-7). Table: `recommendation`.
- **Phase 10 — Multi-Cloud and Hardening.** Real EKS/AKS/GKE via kubeconfig only, multi-tenancy
  activated through the `org_id` columns already reserved in the schema, production hardening.

---

## 5. Phases 11–15 — define these before building them

The repository currently defines only 10 phases. Before writing any Phase 11+ code, **propose
Phases 11–15 and get them approved.** For each phase give: name, goal, the FRs or new requirements
it satisfies, database changes, API surface, and its explicit definition of done. Write the approved
set into `README.md` and a new `docs/phase-11-15-scope.md`.

Candidate directions, consistent with the existing architecture — evaluate and argue for or against
each rather than accepting them as given:

- Observability and operator UX: live SSE streams, incident timeline, graph visualisation in `web/`
- Policy-as-code: versioned, testable, dry-runnable policies with a simulation mode
- Autonomy maturation: a promotion path SUGGEST → APPROVE → AUTO with measured confidence gates
- Cost intelligence: real cloud billing ingestion, cost-per-SLO, budget enforcement
- Platform hardening: multi-tenancy enforcement, SSO/OIDC, secrets management, compliance audit export
- Scale and performance: graph and telemetry benchmarks against the stated NFR targets

---

## 6. Start here

1. Read `README.md`, then `docs/phase-1-architecture/00-phase-1-checklist.md`.
2. Read the Go backend files listed in section 1 — that is the behaviour you must reproduce.
3. Read the three existing `control-plane/` files.
4. Check free disk space.
5. Run the `control-plane` build, fix what breaks, and report exactly what you found before writing
   any new code.

Do not skip step 5. The current state of `control-plane/` is unverified, and the first honest thing
to establish is whether it builds at all.
