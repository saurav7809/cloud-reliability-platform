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
| 5 — Evaluation Engine (synthetic probes, SLO evaluation, error budgets, reliability scoring) | ✅ done |
| 6 — Experiment Engine (chaos, safety rules, steady-state hypothesis, always restores) | ✅ done |
| 7 — Dependency & Propagation (service graph, blast radius, SPOF, critical path) | ✅ done |
| 8 — Root Cause Analysis (multi-signal correlation, explainable verdicts, measured accuracy) | ✅ done |
| 9 — Optimization Advisor (cost + performance advice that never trades reliability silently) | ✅ done |
| 10 — Multi-Cloud & Hardening (enforced multi-tenancy, cloud-agnostic boundary guarded by tests) | ✅ done |

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
ai-service/     Python 3 / FastAPI — anomaly detection, forecasting, RCA re-ranking
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

### Container build and deployment history

The platform can now produce the images it runs, and remembers what it deployed.

**It built its own workload.** From
`github.com/saurav7809/cloud-reliability-platform` at `workloads/sample-service`,
pushed to the local registry, then deployed:

```
POST /api/v1/builds     -> RUNNING   (Kaniko Job in aegiscloud-builds)
GET  /api/v1/builds/{id}-> SUCCEEDED "image pushed to the registry"
registry catalog        -> {"tags":["registry-test","built-by-platform"]}
catalog now runs        kind-registry:5000/aegiscloud/sample-service:built-by-platform
```

**Kaniko rather than `docker build`, deliberately.** Shelling out to Docker means
mounting a Docker socket into the control plane, and a socket is root on the host. A
component trusted with production clusters should not also hold the key to the machine
it runs on. Running the build as a Kubernetes Job also keeps the architectural rule
intact: the same code builds on kind and on EKS, and progress is visible to anyone with
cluster access rather than only in the platform's logs.

**Deployment history and rollback.** Every rollout is recorded with the image it
replaced — captured at deploy time, because "what was running before" stops being
answerable the moment the object is overwritten. Failed rollouts are recorded too: a
failed deployment is what an incident investigation most wants to find.

Rollback reads its target from history rather than from the caller. Requiring someone
to remember the previous tag at the moment they are least able to is how a rollback
becomes a second outage:

```
one deployment recorded  -> refused: "no earlier successful deployment to roll back to"
two recorded             -> rolled back to ...:built-by-platform without being told which
```

**Known limits, stated rather than hidden.** Builds clone public repositories over
HTTPS; private ones need secrets management the platform has not built, and accepting a
token in a request body would be worse than the limitation. A build whose Job vanished
before completion is recorded FAILED, not RUNNING — the honest statement is that the
outcome was not observed, and an unobserved build must never read as a success.



### Sign-up and user management

Self-service registration, colleague invites, and role changes.

**Why open sign-up is safe here, and would not have been two phases ago.** Signing up
creates a *new organisation*, and tenant isolation is enforced inside every query — a
stranger who registers gets an empty organisation and sees no cluster, service or
incident belonging to anyone else. Verified on the live platform: a fresh account reads
`clusters=0 services=0 targets=0`. Before that boundary existed, this endpoint would
have handed any passer-by the whole fleet. It can still be switched off with
`AEGISCLOUD_SIGNUP_ENABLED=false`, which is what a single-company deployment wants.

**Password rules: length, not symbols.** Twelve characters minimum and no composition
requirements, following NIST's reasoning — `Passw0rd!` satisfies every
upper-lower-digit-symbol rule ever written and is on every cracking list, while
`these are the days of miracle` is not. The one substring rule that earns its place
rejects a password containing your own email address.

Observed refusals:

```
"nope"                          -> a valid email address is required
"Passw0rd!"                     -> must be at least 12 characters; length matters
                                   more than symbols, so a memorable phrase is fine
samantha / "samantha12345"      -> the password must not contain your email address
admin@aegiscloud.local          -> an account already exists for that address
```

**Invites and roles.** The first account in an organisation is necessarily ADMIN —
somebody has to invite the second, and an organisation whose only user cannot
administer it is a support ticket by construction. The organisation comes from the
caller's token and never from the request body, because an organisation id in the body
would let one administrator create accounts inside another tenant.

Verified: an invited OPERATOR can list members (200) but cannot invite (403) or read
the audit trail (403); and the last remaining administrator cannot demote themselves —
*"this is the organisation's only administrator; promote someone else first"* — because
an organisation with no ADMIN is locked out of itself with no way back.

Sign-up is audited as an ENGINE action rather than a USER one: there is no
authenticated caller during registration, and attributing it to the account being
created would claim they authorised something before they existed.



### Closing the last four requirements

Four requirements the platform had stated and not met. Nothing else from the
architecture diagram was built — see *Deliberately not built* below.

**FR-15 — metric ingestion.** Two routes into the same `metric_sample` table the
probes write to, so SLO evaluation, scoring, RCA and the AI service treat pushed and
probed data identically rather than growing a second code path each. Verified:

```
POST /targets/{id}/metrics   4 samples -> accepted 2, rejected 2
                             "rejected unknown metric type: NONSENSE"
                             "rejected LATENCY_MS with no value"
```

That second rejection was a bug caught by running it: the request record used a
primitive `double`, so a JSON `null` became `0.0` and a null latency was stored as a
0ms reading — a number that looks like a measurement and drags every percentile down.
The Prometheus pull route refuses a multi-series result for the same reason: three
series mean the query did not identify one thing, and storing the first attributes one
pod's number to a whole target.

**FR-39 — alerts from burn rate.** Raised by the platform itself, unprompted:

```
CRITICAL  auth-service @ aegiscloud-local is burning its AVAILABILITY error budget
          14.9x faster than sustainable; at this rate the budget is gone within a
          day (0.0% left)
```

Severity follows how fast the budget disappears, not how bad the number looks. A
dramatic rate computed from fewer than ten samples raises nothing — a 50x burn from
four probes is a rumour, and paging on it is how alerting loses its credibility.
Alerts auto-resolve when the rate recovers, because an alert that stays open after the
problem is gone teaches people that open alerts mean nothing.

**FR-41 — grouping under a root cause.** The alert above was attached to the incident
that explains it. This is the half that matters operationally: when one service fails,
every service downstream breaches its own SLO and raises its own alert, so the moment
the platform is most useful is the moment it produces the most noise.

**FR-42 / FR-43 — audit everything.** Cluster registration, deployments, target
registration, autonomy changes, policy changes, experiments, applied recommendations
and metric ingestion are now recorded, alongside the engine's own actions in the same
table with the same shape:

```
USER    SET_POLICY        admin@aegiscloud.local  {maxReplicas: 8}   was {maxReplicas: 10}
USER    SET_AUTONOMY      admin@aegiscloud.local  {level: SUGGEST}
USER    INGEST_METRICS    admin@aegiscloud.local  {source: OTEL, accepted: 2, rejected: 2}
ENGINE  SCALE_DOWN        platform                {fromReplicas: 3, toReplicas: 2}
ENGINE  ESCALATE          platform
```

Auditing never fails a request: a write that succeeded with a missing audit row is a
recoverable gap, while a request rolled back over bookkeeping is an outage. The
failure is logged as `AUDIT GAP` and the request stands.

### Deliberately not built

Prometheus, Loki, Tempo, Grafana, OpenCost, Chaos Mesh, HPA/KEDA integration, ingress
management and capacity planning appear in the architecture diagram and are **not**
implemented. None is required by FR-1 to FR-45. Where their absence limits something,
the platform says so at the point of use rather than in a footnote: cost savings read
$0.00 without OpenCost, and the Experiment Engine refuses network and resource-pressure
faults rather than approximating them without Chaos Mesh.



### Python AI Service

Anomaly detection, forecasting and RCA re-ranking, running as a FastAPI sidecar the
control plane treats as optional. Observed against the platform's own probe history:

```
anomalies   173 real latency samples, median 13ms
            "366 is 54.1 robust deviations above the median of 13"

forecast    against the target's own 250ms SLO
            "level 12.9 with a +0.48 per-sample drift that is smaller than the
             noise in the series; no breach estimate is meaningful"

re-rank     auth-service  platform 0.657 -> adjusted 0.807  (+0.15, the cap)
            "telemetry is 28.5 deviations from this service's own baseline, which
             the platform's threshold comparison cannot see"
```

**No model, and the code says so.** MAD-based detection and Holt linear trend, chosen
because a probe series is tens of points a minute apart — a neural model on that would
need orders of magnitude more data and could not explain itself to an operator at three
in the morning. `/health` names the methods in use so a verdict can always be traced to
what produced it.

**A flaw the tests caught.** The first forecaster reported "crosses 250ms in about 377
samples" for a *flat, noisy* series: real arithmetic performed on jitter. It already
knew its own confidence was low and wasn't acting on it. It now withholds the breach
estimate entirely and says why.

**The re-ranker cannot overrule topology.** Its adjustment is capped at 0.15 and halved
for candidates the graph places downstream, so unusual telemetry can reorder candidates
the platform found comparable but can never lift a symptom above its cause. It also
cannot introduce a candidate, having no database to invent one from.

**One bug found by running it.** Java's HttpClient defaults to HTTP/2 and opens with an
h2c upgrade that uvicorn rejects, mangling the request into a 422 that reads exactly
like a validation failure in a perfectly valid body. Pinned to HTTP/1.1.



### Phase 10 — Multi-Cloud & Hardening

**Multi-tenancy is now enforced, not reserved.** The `org_id` columns existed from
Phase 1 and nothing used them: any authenticated user could read every organisation's
clusters, services, graph, incidents and recommendations. The organisation is now part
of the JWT identity and part of every tenant-facing query.

Verified with two real tenants in the same database:

```
tenant                clusters                      services   graph        incidents
AegisCloud            4 (incl. aegiscloud-local)    15         15 svc/19 e  1
Northwind Labs        1 (northwind-prod)            1          1 svc/0 e    0
```

And with tenant B holding tenant A's genuine ids:

```
POST /alerts/{A's alert}/acknowledge   as B -> 404      as A -> 200
GET  /services/{A's service}/blast-radius  as B -> 404
GET  /incidents/{A's incident}             as B -> 404  as A -> 200
```

Four decisions worth stating:

- **Scoping lives in the SQL**, not in a filter applied to results. Filtering
  afterwards means the wrong rows were already fetched and already counted in an
  aggregate, one forgotten line away from being returned.
- **A cross-tenant id returns 404, not 403.** Telling a caller that an id exists but
  is not theirs confirms the id.
- **A token with no organisation is rejected** rather than defaulted to one. Tokens
  issued before tenancy existed are exactly what a default would admit.
- **The overview cache key carries the organisation.** A single shared key would have
  served one tenant's rollup to another for the length of the TTL — the quietest
  possible leak, since every individual query was scoped correctly.

Engines that run on a timer have no caller and therefore no tenant; the optimization
advisor now iterates organisations explicitly rather than running as whichever one
happened to be first.

**The cloud-agnostic boundary is guarded by tests, not by intent.** Three checks fail
the build if the central architectural claim is broken: no cloud provider SDK is
imported anywhere, no engine branches on which provider a cluster belongs to, and
Kubernetes clients are built only by `KubernetesClientFactory`. This is the honest
form of "multi-cloud" available without cloud accounts: EKS, AKS and GKE differ from
the kind cluster by a kubeconfig context and a label, and now nothing can quietly make
them differ by more.

**Also fixed:** the operator console was lying about the platform's own state — the
engine-status panel still reported the Deployment Engine as `NOT_IMPLEMENTED` and
telemetry as "arrives in Phase 5", six phases after both shipped. Statuses are now
derived from what each engine has actually recorded, per organisation, and the RCA and
Optimization engines were added to the list.

**Not done, and not claimed:** no EKS, AKS or GKE cluster has been registered, because
that needs cloud accounts this machine does not have. What is verified is that
registration takes any kubeconfig context and that no code path distinguishes one
provider from another.



### Phase 9 — Optimization Advisor

Cost and performance advice, with one rule above the rest (FR-33, UC-7): reliability
is not currency. Observed against the running fleet:

```
auth-service      REPLICA_REDUCTION   safe=false   "Not safe to reduce replicas yet"
catalog-service   SCALING_STRATEGY    safe=false   TREND scaling but no latency SLO
checkout-service  OBSERVABILITY_GAP   safe=false   nothing is measuring it
```

**Withholding, not warning.** `auth-service` was genuinely over-provisioned at 0.6%
CPU, and the advisor computed the saving — then declined to offer it, because the
error budget was at 0% after the outages of the previous phases. Asking the API to
apply it anyway is refused:

> this recommendation is not offered as safe to apply: CPU sits at 0.6% of request
> across 2 replicas... Withheld: only 0.0% of the error budget remains, so removing
> capacity now would spend reliability the service cannot currently spare.

There is no confirm-anyway path. A warning gets skimmed; an absent recommendation
cannot be applied by accident. The saving is still stated in full, so nothing is
hidden — it is simply not offered as a thing to do.

**Applying is governed like any other write.** Three refusals stand between advice and
a cluster: the advisor's own safety verdict, the recommendation still being open, and
the same Policy Engine check the autonomous loop passes. An operator agreeing with a
recommendation does not make it within policy.

**Dismissals are kept.** `"reviewed: waiting for the error budget to recover first"`
stays on the record against the recommendation, because FR-34 asks for bad advice to
remain visible after someone acted on it.

**Advice requires measurement.** No CPU reading, no resource recommendation — the
advisor says nothing rather than inferring utilisation from a replica count. Targets
nothing has probed get an `OBSERVABILITY_GAP` finding instead, which names their cost
and tells the operator to register an endpoint.

**Known limitation.** Estimated savings for the kind-cluster targets read $0.00
because `monthly_cost_usd` is only populated for the seeded demo fleet; real cost data
needs the OpenCost integration, which is not built. The arithmetic is exercised by
tests with real figures ($400/4 replicas → $100 saving), but the live numbers are
honest zeros rather than invented ones.



### Phase 8 — Root Cause Analysis

Ranked candidate causes from four signal classes — graph position, temporal order,
change events, resource saturation — each verdict carrying the facts it rests on.

**A real diagnosis.** `auth-service` was taken to zero replicas; once its measured
score fell, diagnosing produced:

```
auth-service, likely cause (confidence 0.66)
  TEMPORAL_ORDER       degraded first, at 2026-09-01T06:13:39Z
  CHANGE_EVENT         4 changes near the incident: scaled 2 -> 1 on cpu; ...;
                       ESCALATED auth-service-744b6794fc-tdzng (IMAGE_PULL_FAILURE)
  RESOURCE_SATURATION  reliability score fell 94.3 points
```

**Accuracy against ground truth.** Chaos runs are the only incidents whose true cause
the platform knows, because it caused them. `GET /api/v1/rca/accuracy` re-analyses
each run's window and checks whether the top verdict names the service that was
actually broken: **1 of 1 scored correct**. Runs where nothing degraded measurably are
reported as unscored rather than counted either way — a chaos run the system shrugged
off has no incident to diagnose, and scoring it would move the number without
measuring anything.

**FR-29 is structural, not a filter.** Confidence is derived *from* the evidence list,
so a candidate with no supporting facts has no confidence to compute and never becomes
a verdict. Verified: an isolated service with no graph position, no timing, no changes
and healthy pods produces no verdict at all.

**Symptoms are labelled, not discarded.** An early draft dropped low-confidence
candidates, which threw away the most useful output in the three-alerts-one-cause
incident: telling the operator that two of the three are downstream and need no
separate investigation. Downstream candidates are now kept and marked
`LIKELY_SYMPTOM` with the counter-evidence stated.

**Three bugs found by running it, all of the same family — seeded fixtures being
mistaken for measurements:**

1. Diagnosis opened an incident for a service scoring 100, because it read the
   denormalised score column, which holds a number for every target including ones
   nothing has ever probed. Candidacy now requires a `reliability_score_snapshot` row,
   which exists only because something was measured.
2. The configured score window was decorative: scoring always read a day of samples,
   so a service that went down five minutes ago still scored in the nineties.
   Detection would have taken hours. The window is now honoured, and SLO windows stay
   long on purpose — a budget that forgets last week is not a budget.
3. The accuracy harness scored a seeded `NETWORK_PARTITION` row — a fault type the
   engine cannot even inject. Ground truth is now restricted to runs the platform
   actually executed and recorded.

**The Intelligence Layer never writes to a cluster.** Every cluster call in it is a
read, and there is exactly one: listing pods. A wrong diagnosis stays a wrong sentence
on a screen; only the policy-gated Control Plane acts.



### Phase 7 — Dependency & Propagation

The graph, built over the real `microservices-demo` topology discovered in Phase 3
(15 services, 19 declared edges):

```
entry points        loadgenerator, checkout-service, shoppingassistantservice
critical path       loadgenerator -> frontend -> checkoutservice -> shippingservice
                    -> currencyservice
single points of    frontend        cuts 9 services off from every entry point
failure             checkoutservice cuts 2
largest blast       currencyservice 4 affected · productcatalogservice 4 · cartservice 3
radius
```

Every one of those is computed by removal and traversal, not by counting edges.
A single point of failure is found by taking the service out and seeing what can no
longer be reached from any entry point — which is why `auth` and `catalog` in the
test topology are correctly *not* flagged: the database below them is reachable
through either, so neither alone isolates anything.

**Direction is the thing that matters.** An edge `A -> B` means A calls B, so failure
travels backwards: blast radius walks the reverse graph. Getting that inverted
produces an answer that is confidently and exactly wrong, so it has its own test.

**Discovery from experiments.** A `DEPENDENCY_OUTAGE` experiment now records the edges
it demonstrates. Taking `auth-service` to zero and watching `catalog-service`
throughout produced: *"catalog-service held up (100.0 -> 100.0); no dependency
recorded"* — which is correct, since the two sample workloads genuinely are
independent. An absent edge is honest; an invented one produces a blast radius that
looks authoritative and is wrong.

This is a stronger signal than a trace, and a narrower one. A trace shows that A
called B; an experiment shows that A stops working when B does — which is what a
dependency edge actually claims. It cannot find dependencies nobody has experimented
on, so `MANUAL` and (once tracing is deployed) `TRACE` edges remain the broader source.

**A gap this phase exposed.** The platform could deploy a workload but had no way to
register it as a managed target, so a rolled-out service was invisible to scaling,
healing, evaluation and experiments — all of which read `deployment_target`.
`POST /api/v1/targets` closes that, and refuses to register a target whose workload is
not actually running.

**Scale.** 200 services and 1000 edges: blast radius, criticality ranking and the full
single-point-of-failure sweep complete well inside the interactive-latency target,
with cycles, disconnected components and unknown edge endpoints all covered by tests.



### Phase 6 — Experiment Engine

Break something on purpose, measure what happened, put it back. Observed against the
kind cluster:

**Safety refuses before anything is broken**
- `POD_KILL` of 3 of 3 replicas: *"exceeds the 50% blast-radius limit (at most 1)"*.
- A 3600s run: *"exceeds the 900s maximum: a fault left injected longer than that
  cannot be reliably undone by hand if the platform dies mid-run"*.
- Both refusals are recorded as `REJECTED_BY_POLICY` runs with their reason, so a
  request that was declined is as visible as one that ran.

**A run that completes**
- `REPLICA_LOSS`: replicas went 3 → 2 → 3, hypothesis held, score 90.7 → 90.8 → 91.4,
  and the fault spec records exactly what was injected and that it was restored.
- `POD_KILL`: one pod deleted, *"nothing to restore: the ReplicaSet recreates deleted
  pods"*, score 92.2 → 92.3 → 92.8.

**A run that aborts itself**
- With an abort threshold of 99.9 against a real score of 91.7, a run requested for
  300s ended after **0.6s**: *"steady-state hypothesis broken"* — and restored.

**Two bugs the first real run exposed, both now fixed**

The control loop and the Experiment Engine were each unaware of the other:

1. The loop scaled down, an experiment then reduced readiness, and the loop's
   verification blamed **its own action** and rolled back a correct decision.
   Verification now discards any window a chaos run overlapped:
   *"verification inconclusive: a chaos experiment overlapped this window"*.
2. The loop kept autoscaling a target that was under experiment, fighting the
   injected fault and making the result unreadable. Scaling now pauses for a target
   with a run in flight: *"scaling paused: a chaos experiment is running on this
   target"*. Healing deliberately keeps running — a pod that fails for an unrelated
   reason still deserves fixing.

**What is deliberately not implemented.** Network latency, packet loss, partitions and
in-container CPU/memory pressure need a privileged node agent — that is what Chaos
Mesh installs. They are left unimplemented rather than approximated, because
restarting a pod is not a network partition, and Phase 8's RCA will be scored against
these experiments' recorded causes.



### Real-time detection (Kubernetes watches)

The control loop no longer waits for its own timer to notice a failure. Informers
watch pods in every namespace that has targets, and an unhealthy pod reconciles that
target immediately — through the same `reconcileTarget` path, and therefore the same
policy and autonomy checks, that the scheduled sweep uses. The sweep remains as the
backstop for anything a dropped watch missed.

Measured with the polling interval deliberately set to **10 minutes**, so nothing
observed could have come from the timer:

```
10:49:50.220  image changed to a tag that does not exist
10:49:54.088  watch: unhealthy pod reported, reconciling now      (+3.9s)
10:49:55.411  escalated: "pod cannot obtain its image (ErrImagePull);
              a restart would fail identically"                   (+5.2s)
```

Detection in under four seconds against a sixty-second polling floor, and the burst
of events a crash loop produces is debounced so one failure does not become a
stampede of reconciliations.



### Phase 5 — Evaluation Engine

The phase that replaces fixtures with measurements. Observed against the kind cluster:

**Probing a service that has no ingress**
- Endpoints are addressed either as ordinary URLs or as
  `k8s://namespace/service:port/path`, which routes through the Kubernetes API
  server's service proxy. A ClusterIP service with no NodePort and no port-forward
  is probed exactly as it stands, over the same authenticated API used everywhere
  else — so this works identically against kind and EKS.
- A real probe of `auth-service` returned HTTP 200 in **16-23ms**.

**A correctness fix the first run exposed**
- The first implementation built a Kubernetes client per probe, so every measurement
  paid for a fresh TLS handshake: readings came back at 274ms and a p95 of 1916ms.
  That is the prober measuring itself. Clients are now pooled per cluster, and the
  same probe reports 23ms.

**SLO evaluation and error budgets**
- Availability and latency SLOs registered against the target, evaluated over their
  windows: *"30 of 30 measurements met the availability objective (100.00%), 100.0%
  of budget left, burn 0.00x"* and *"p95 is 23ms against a 250ms objective"*.
- Reliability Score climbed **70 → 100** as real samples accumulated and the cold
  -start outlier aged out of the percentile, with every component reported alongside
  it: `{availability=100.0, latency=100.0, errorRate=100.0}`.

**The failure path**
- Scaling `auth-service` to zero replicas made the probe fail with HTTP 503.
  Availability fell to **77.5%**, the error budget hit **0% remaining** and a burn
  rate of **45x**, and the score dropped to 84.3.
- The latency SLO stayed at 31 samples while availability counted 40 — failed probes
  never enter a latency percentile, because the time a request took to fail is not a
  latency measurement.
- `deployment_target` now carries measured readings (score 85.4, availability 79.17%,
  p95 23.1ms) rather than seeded ones.

**Tests** — 61 pass, covering burn-rate arithmetic at and beyond the objective,
zero-tolerance objectives without dividing by zero, nearest-rank percentiles, score
renormalisation when a component was never measured, and cluster-address parsing.



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
