"""Re-ranking root-cause candidates using signals the control plane cannot see.

The control plane already correlates graph position, timing and change events. What
it cannot judge is how *unusual* a service's own telemetry looks, because it compares
readings against fixed thresholds while this service compares them against the
service's own recent distribution. A service sitting at 400ms is breaching if its
objective is 250ms — but it is far more interesting if it has spent the last hour at
40ms, and the threshold cannot tell the difference.

Two constraints shape this module.

It cannot introduce a candidate. Only services the control plane already nominated
appear in the output, because this service has no way to know any others exist. That
is what stops a statistical opinion from turning into a fabricated one.

It cannot silently overrule the graph. A service the graph places downstream of
another failure has structural evidence against it, and no amount of statistical
strangeness makes a symptom into a cause. The adjustment is bounded so it can reorder
candidates the platform found comparable, and cannot promote a symptom above the
service that explains it.
"""

from __future__ import annotations

from dataclasses import dataclass

# The most the statistical view may move a candidate's confidence, in absolute terms.
# Bounded deliberately: this signal is one of four, and the only one computed without
# any knowledge of the system's topology.
MAX_ADJUSTMENT = 0.15

# An anomaly score at or beyond this counts as fully unusual for scaling purposes.
_SATURATING_SCORE = 8.0


@dataclass(frozen=True)
class Candidate:
    service_id: str
    service_name: str
    confidence: float
    upstream_of: int = 0
    downstream_of: int = 0
    change_events: int = 0
    anomaly_score: float | None = None


@dataclass(frozen=True)
class Ranked:
    candidate: Candidate
    adjusted_confidence: float
    moved_by: float
    reasoning: str


def rerank(candidates: list[Candidate]) -> list[Ranked]:
    """Adjusts each candidate's confidence by how unusual its telemetry is."""
    ranked: list[Ranked] = []

    for candidate in candidates:
        adjustment, reason = _adjustment(candidate)
        adjusted = max(0.0, min(1.0, candidate.confidence + adjustment))

        ranked.append(
            Ranked(
                candidate=candidate,
                adjusted_confidence=adjusted,
                moved_by=round(adjusted - candidate.confidence, 3),
                reasoning=reason,
            )
        )

    ranked.sort(key=lambda item: (-item.adjusted_confidence, item.candidate.service_name))
    return ranked


def _adjustment(candidate: Candidate) -> tuple[float, str]:
    if candidate.anomaly_score is None:
        return 0.0, "no telemetry anomaly score available; platform ranking left unchanged"

    strength = min(candidate.anomaly_score / _SATURATING_SCORE, 1.0)

    if candidate.downstream_of > 0:
        # A symptom with strange telemetry is still a symptom — that is what being
        # downstream of another failure means. The adjustment is halved and capped
        # so unusual numbers cannot lift a victim above its cause.
        adjustment = MAX_ADJUSTMENT * strength * 0.5
        return adjustment, (
            f"telemetry is {candidate.anomaly_score:.1f} deviations from its own baseline, "
            f"but the graph places this service downstream of {candidate.downstream_of} "
            f"other failure(s), so the increase is halved"
        )

    if strength < 0.25:
        return 0.0, (
            f"telemetry is only {candidate.anomaly_score:.1f} deviations from baseline, "
            f"which is within normal variation; ranking left unchanged"
        )

    adjustment = MAX_ADJUSTMENT * strength
    return adjustment, (
        f"telemetry is {candidate.anomaly_score:.1f} deviations from this service's own "
        f"baseline, which the platform's threshold comparison cannot see"
    )
