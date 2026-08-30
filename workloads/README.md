# Sample Workloads

A deliberately failable workload for exercising AegisCloud end to end.

A healthy container proves nothing about a control plane — Auto-Scaling, Self-Healing, SLO burn
and chaos experiments are all invisible against a service that never misbehaves. This one
misbehaves on request.

The chaos endpoints live in the **workload**, never in the platform. AegisCloud stays a pure
observer and controller, exactly as the Phase 1 design specifies.

## What gets deployed

One 13 MB Go image (`aegiscloud/sample-service:dev`, distroless, zero dependencies), deployed
three times with different configs:

| Service | Replicas | Memory limit | Notes |
|---|---|---|---|
| `checkout-service` | 2 | 128Mi | healthy at boot |
| `catalog-service` | 2 | 128Mi | **starts degraded** — 300ms latency, 8% errors |
| `auth-service` | 1 | 96Mi | tighter memory, so OOM is easy to trigger |

`catalog-service` boots pre-degraded on purpose, so the fleet is not uniformly green on first
run and SLO burn has something real to show.

## Deploy

```bash
./workloads/deploy.sh
```

Builds the image, side-loads it into kind (`kind load` — nodes cannot pull from your local
Docker daemon, and skipping this leaves pods in `ErrImagePull`), applies the manifests, and
waits for rollout.

## Endpoints

| Endpoint | Effect | Exercises |
|---|---|---|
| `GET /healthz` | Readiness probe | Kubernetes probes |
| `GET /metrics` | Prometheus text format | Evaluation Engine ingestion (Phase 5) |
| `GET /api/work?cpu=<ms>` | Burns CPU for N ms | **Auto-Scaling** on CPU |
| `POST /chaos/latency?ms=<n>` | Injects latency per request | Latency SLO breach → **alert** |
| `POST /chaos/error-rate?pct=<n>` | Returns 500 for N% of requests | Availability SLO burn |
| `POST /chaos/unready?on=<bool>` | Fails readiness without dying | Pod drops from Service endpoints |
| `POST /chaos/leak?mb=<n>` | Retains memory until OOMKilled | **Self-Healing** on `OOMKilled` |
| `POST /chaos/crash` | `exit(1)` → CrashLoopBackOff | **Self-Healing** on crash |
| `POST /chaos/reset` | Clears all injected chaos | Return to healthy |

## Driving it

```bash
kubectl port-forward -n aegiscloud svc/checkout-service 9090:80
```

Then in another shell:

```bash
curl -X POST "localhost:9090/chaos/latency?ms=500"
```

## Verified behaviour

All of the following were confirmed running on the local kind cluster, not simulated:

| Test | Result |
|---|---|
| `catalog-service` boots degraded | `app_chaos_latency_ms 300`, `app_chaos_error_rate_pct 8`; a request took 321ms |
| Latency injection | 400ms injected → request time 0.417s |
| Error injection at 100% | HTTP 500 |
| Unready toggle | `/healthz` → 503, then 200 after reset |
| `POST /chaos/crash` | pod terminated `Error exit=1`, `restartCount` 0 → 1, back to `Running` |
| `POST /chaos/leak?mb=200` vs 96Mi limit | pod terminated **`OOMKilled`**, restarted automatically |

Those last two are exactly the events the Self-Healing controller will consume in Phase 4 — real
`CrashLoopBackOff` and `OOMKilled` reasons from the kubelet, not fixtures.

## Note on scale

The kind cluster is a single node on your laptop. Five pods is comfortable; do not plan a 30-pod
fleet here.
