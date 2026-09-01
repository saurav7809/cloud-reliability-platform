# The live system

Four real services, built from `workloads/sample-service`, deployed to the kind
cluster **through the platform's own API** and measured by it. There is no seeded
data behind any number the dashboard shows for them.

```
storefront ──► orders ──► payments
     │            │
     └────────────┴──────► catalog
```

Each service calls its dependencies over the network for every request, sequentially,
so latency accumulates the way it does in a real request path and a failure in a leaf
surfaces as a failure in everything above it. That propagation is the whole point: a
declared dependency proves nothing, while a service that returns 503 because its
dependency is gone is evidence.

## Bringing it up

```bash
docker build -t aegiscloud/sample-service:v2 workloads/sample-service
kind load docker-image aegiscloud/sample-service:v2 --name aegiscloud-local
```

Then, entirely through the API — declare, deploy, register, measure:

```bash
POST /api/v1/services                      {"name":"catalog","ownerTeam":"Catalog"}
POST /api/v1/deployments                   {"workload":"catalog","image":"aegiscloud/sample-service:v2",
                                            "env":{"DEPENDENCIES":"..."}}
POST /api/v1/targets                       {"serviceId":"...","clusterName":"aegiscloud-local"}
POST /api/v1/targets/{id}/endpoints        {"address":"k8s://aegiscloud-live/catalog:80/api/work"}
POST /api/v1/targets/{id}/slos             {"sliType":"AVAILABILITY","objectiveValue":99.0}
```

The probe address deliberately targets `/api/work` rather than `/healthz`. Liveness is
self-only — a pod must not be restarted because something it calls is down — while
`/api/work` is what the service actually promises to do, and it fails when a dependency
fails. Probing the honest endpoint is what makes availability mean something.

## What was observed

Taking `catalog` to zero through a `DEPENDENCY_OUTAGE` experiment, the platform
discovered the graph by watching what broke:

```
orders     degraded 100.0 -> 88.3 while catalog was down; recorded orders -> catalog
storefront degraded 100.0 -> 72.5 while catalog was down; recorded storefront -> catalog
payments   held up (100.0 -> 100.0); no dependency recorded
auth-service held up (95.6 -> 95.8); no dependency recorded
```

Then, with `catalog` returning errors from its own chaos endpoint — the workload
misbehaving, not the platform acting on it — three services degraded and RCA was asked
which one was actually broken:

```
#1  catalog     LIKELY_CAUSE     0.52   upstream of 2 of the 2 other degraded services
#2  storefront  LIKELY_SYMPTOM   0.37   downstream of 1 other degraded service
#3  orders      LIKELY_SYMPTOM   0.00   downstream of 1 other degraded service
```

The temporal signal was **wrong** here — it reported catalog as degrading 84s *after*
the first failure, because storefront sits upstream in the request path and its probe
fails at the same moment. Graph position outvoted it. That is precisely why the engine
correlates four signal classes rather than trusting whichever one is loudest.
