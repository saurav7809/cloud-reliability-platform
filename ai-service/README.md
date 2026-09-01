# AegisCloud AI Service

Anomaly detection, forecasting and RCA re-ranking over telemetry the control plane
has actually measured.

```bash
pip install -r requirements.txt
python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
python -m pytest tests -q
```

The control plane finds it at `AEGISCLOUD_AI_BASE_URL` (default `http://localhost:8000`)
and treats it as optional, exactly like Redis: when it is down, the analytics endpoints
say so rather than returning an empty result that looks like "nothing is wrong".

## What it does, and what it deliberately does not

There is no language model here and no trained network. A probe series is tens to
hundreds of points arriving a minute apart, and on that data classical statistics are
both more accurate and more auditable than a model whose confidence nobody can check.
The methods in use are named in `/health` so the platform can always say what produced
a verdict.

| Endpoint | Method | Why this one |
|---|---|---|
| `POST /anomaly` | Median absolute deviation, robust z-score | One 2000ms spike drags a mean up and inflates a standard deviation enough that the spike stops looking extreme — it hides itself. The median barely moves. |
| `POST /forecast` | Holt linear trend | The breach time is what an operator can act on. Returned as absent when the trend is smaller than the noise it was measured through, rather than as a confident-looking number extrapolated from jitter. |
| `POST /rca/rerank` | Evidence-weighted adjustment | Adds how unusual a service's telemetry is against its own baseline — something the control plane cannot see, because it compares against fixed thresholds. |

## Two constraints on the re-ranker

It cannot introduce a candidate. Only services the control plane already nominated
appear in the output, because this service has no database and no way to know any
others exist. That is what stops a statistical opinion from becoming a fabricated one.

It cannot overrule the graph. A service the graph places downstream of another failure
has structural evidence against it, and no amount of statistical strangeness makes a
symptom into a cause. The adjustment is capped at 0.15 and halved for downstream
candidates, so it can reorder candidates the platform found comparable and cannot
promote a victim above its cause.
