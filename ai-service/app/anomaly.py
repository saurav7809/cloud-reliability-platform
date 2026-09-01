"""Anomaly detection over a metric series.

The method is a median-absolute-deviation (MAD) robust z-score, and the choice is
deliberate rather than a placeholder for something cleverer.

A probe series here is tens to hundreds of points, arriving a minute apart, and the
thing being detected is usually a step change or a spike. A neural model trained on
that would be theatre: it needs orders of magnitude more data than exists, and its
verdicts could not be explained to an operator at three in the morning. The platform's
rule throughout is that a verdict must cite its evidence, and "this point is 6.2
deviations from the median of the last hour" is evidence a person can check.

MAD rather than mean and standard deviation because the thing being detected corrupts
the statistic used to detect it: one 2000ms spike drags the mean up and inflates the
standard deviation, so the spike hides itself. The median barely moves.
"""

from __future__ import annotations

from dataclasses import dataclass
from statistics import median

# Scale factor making MAD a consistent estimator of the standard deviation for
# normally distributed data, so the threshold below reads like a familiar z-score.
_MAD_TO_SIGMA = 1.4826

# Below this many points there is no distribution to speak of, and any verdict would
# be an opinion about three numbers.
MINIMUM_POINTS = 8


@dataclass(frozen=True)
class Anomaly:
    index: int
    value: float
    score: float
    direction: str
    detail: str


@dataclass(frozen=True)
class AnomalyReport:
    anomalies: list[Anomaly]
    baseline: float
    spread: float
    points_examined: int
    detail: str

    @property
    def found(self) -> bool:
        return bool(self.anomalies)


def detect(values: list[float], threshold: float = 3.5) -> AnomalyReport:
    """Finds points that do not belong to the same distribution as the rest.

    :param threshold: robust z-score above which a point is called anomalous. 3.5 is
        the conventional MAD cut-off; lower it and normal jitter starts reporting.
    """
    if len(values) < MINIMUM_POINTS:
        return AnomalyReport(
            anomalies=[],
            baseline=median(values) if values else 0.0,
            spread=0.0,
            points_examined=len(values),
            detail=f"need at least {MINIMUM_POINTS} points to describe a distribution; "
            f"got {len(values)}",
        )

    baseline = median(values)
    deviations = [abs(value - baseline) for value in values]
    mad = median(deviations)

    if mad == 0:
        # A perfectly flat series: every deviation is zero, so a z-score is undefined.
        # Anything that differs at all is the anomaly, and saying so is more useful
        # than dividing by zero or declaring the series clean.
        differing = [
            Anomaly(
                index=i,
                value=value,
                score=float("inf"),
                direction="above" if value > baseline else "below",
                detail=f"series is otherwise constant at {baseline:g}; this point is {value:g}",
            )
            for i, value in enumerate(values)
            if value != baseline
        ]
        return AnomalyReport(
            anomalies=differing,
            baseline=baseline,
            spread=0.0,
            points_examined=len(values),
            detail="series is constant apart from the points listed",
        )

    spread = mad * _MAD_TO_SIGMA
    anomalies: list[Anomaly] = []

    for index, value in enumerate(values):
        score = abs(value - baseline) / spread
        if score >= threshold:
            direction = "above" if value > baseline else "below"
            anomalies.append(
                Anomaly(
                    index=index,
                    value=value,
                    score=round(score, 2),
                    direction=direction,
                    detail=(
                        f"{value:g} is {score:.1f} robust deviations {direction} the "
                        f"median of {baseline:g}"
                    ),
                )
            )

    return AnomalyReport(
        anomalies=anomalies,
        baseline=round(baseline, 3),
        spread=round(spread, 3),
        points_examined=len(values),
        detail=(
            f"{len(anomalies)} of {len(values)} points exceed {threshold} robust "
            f"deviations from a median of {baseline:g}"
        ),
    )
