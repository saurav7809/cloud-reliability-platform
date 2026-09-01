"""The AegisCloud AI service.

Deliberately narrow. It computes statistics over series the control plane sends it and
sends verdicts back; it holds no database, reaches no cluster, and stores nothing. The
control plane remains the only component that talks to Kubernetes, which keeps the
architectural rule intact: the Intelligence Layer reads and reasons, and only the
policy-gated Control Plane acts.

Every response carries the reasoning that produced it, for the same reason RCA
verdicts do — an unexplainable number cannot be checked, argued with, or improved.

<b>What this service does not do.</b> There is no language model here and no trained
network. On series of tens of points, arriving a minute apart, classical statistics
are both more accurate and more honest than a model whose confidence nobody can audit.
When the platform has months of history, that trade changes; today it does not, and
the code says which methods are in use rather than implying something grander.
"""

from __future__ import annotations

from fastapi import FastAPI
from pydantic import BaseModel, Field

from app import anomaly, forecast, rca

app = FastAPI(
    title="AegisCloud AI Service",
    version="1.0.0",
    description="Anomaly detection, forecasting and RCA re-ranking over platform telemetry.",
)


@app.get("/health")
def health() -> dict:
    """Liveness, and an honest statement of what is loaded.

    The control plane treats this service as optional and degrades when it is absent,
    the same way it treats Redis. Reporting the methods in use means the platform can
    say what produced a verdict without asking a human what was deployed.
    """
    return {
        "status": "ok",
        "methods": {
            "anomaly": "median absolute deviation, robust z-score",
            "forecast": "Holt linear trend (exponential smoothing)",
            "rca": "evidence-weighted re-ranking",
        },
        "models": "none — classical statistics only, see module docstrings for why",
    }


class SeriesRequest(BaseModel):
    values: list[float] = Field(..., description="the metric series, oldest first")
    threshold: float | None = Field(
        default=3.5, description="robust z-score above which a point is anomalous"
    )


@app.post("/anomaly")
def detect_anomalies(request: SeriesRequest) -> dict:
    report = anomaly.detect(request.values, request.threshold or 3.5)
    return {
        "found": report.found,
        "baseline": report.baseline,
        "spread": report.spread,
        "pointsExamined": report.points_examined,
        "detail": report.detail,
        "anomalies": [
            {
                "index": item.index,
                "value": item.value,
                "score": item.score if item.score != float("inf") else None,
                "direction": item.direction,
                "detail": item.detail,
            }
            for item in report.anomalies
        ],
    }


class ForecastRequest(BaseModel):
    values: list[float] = Field(..., description="the metric series, oldest first")
    horizon: int = Field(default=10, ge=1, le=200)
    threshold: float | None = Field(
        default=None, description="the objective or limit that matters, if any"
    )
    direction: str = Field(
        default="above", description="whether breaching means going above or below"
    )


@app.post("/forecast")
def project_series(request: ForecastRequest) -> dict:
    result = forecast.project(
        request.values, request.horizon, request.threshold, request.direction
    )
    return {
        "level": result.level,
        "slopePerStep": result.slope_per_step,
        "projected": result.projected,
        "stepsToBreach": result.steps_to_breach,
        "breachesWithinHorizon": result.breaches,
        "confidence": result.confidence,
        "detail": result.detail,
    }


class RcaCandidate(BaseModel):
    service_id: str
    service_name: str
    confidence: float
    upstream_of: int = 0
    downstream_of: int = 0
    change_events: int = 0
    anomaly_score: float | None = None


class RcaRequest(BaseModel):
    candidates: list[RcaCandidate]


@app.post("/rca/rerank")
def rerank(request: RcaRequest) -> dict:
    """Re-ranks RCA candidates using anomaly strength alongside the platform's signals.

    The control plane's own ranking already correlates graph position, timing and
    change events. What this adds is how unusual each service's own telemetry looks,
    which the control plane cannot see because it compares against thresholds rather
    than against a distribution.

    It re-orders and explains; it never invents a candidate. A service the control
    plane did not consider cannot appear here, because this service has no way to know
    such a service exists — which is the property that keeps a statistical opinion from
    becoming a fabricated one.
    """
    ranked = rca.rerank(
        [
            rca.Candidate(
                service_id=candidate.service_id,
                service_name=candidate.service_name,
                confidence=candidate.confidence,
                upstream_of=candidate.upstream_of,
                downstream_of=candidate.downstream_of,
                change_events=candidate.change_events,
                anomaly_score=candidate.anomaly_score,
            )
            for candidate in request.candidates
        ]
    )

    return {
        "ranked": [
            {
                "rank": index + 1,
                "serviceId": item.candidate.service_id,
                "service": item.candidate.service_name,
                "platformConfidence": round(item.candidate.confidence, 3),
                "adjustedConfidence": round(item.adjusted_confidence, 3),
                "movedBy": item.moved_by,
                "reasoning": item.reasoning,
            }
            for index, item in enumerate(ranked)
        ]
    }
