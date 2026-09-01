"""Forecasting: where a metric is heading, and when it will cross a line.

Holt's linear trend method — exponential smoothing of level and slope. Chosen for the
same reason as the MAD detector: it fits the data that actually exists. Probe series
are short and irregular, with no daily seasonality visible in an hour of samples, so
a seasonal model would be fitting noise and calling it a pattern.

What matters operationally is not the forecast itself but the breach time: "the p95
crosses its 250ms objective in about 22 minutes" is something an operator can act on,
where "the forecast is 249ms" is not. The breach estimate is therefore the headline,
and it is returned as absent — never as a large number — when the trend does not lead
to a breach at all, or when the trend is smaller than the noise it was measured
through.
"""

from __future__ import annotations

from dataclasses import dataclass

# Enough points to separate a trend from two noisy readings.
MINIMUM_POINTS = 5

# Smoothing constants. Level responsive, trend conservative: a metric series is
# mostly level with occasional real movement, and a jumpy trend term turns one
# unusual sample into a confident prediction of disaster.
_LEVEL_ALPHA = 0.4
_TREND_BETA = 0.15


@dataclass(frozen=True)
class Forecast:
    level: float
    slope_per_step: float
    projected: list[float]
    steps_to_breach: int | None
    breaches: bool
    confidence: str
    detail: str


def _holt(values: list[float]) -> tuple[float, float]:
    """Returns the smoothed level and per-step slope at the end of the series."""
    level = values[0]
    trend = values[1] - values[0]

    for value in values[1:]:
        previous_level = level
        level = _LEVEL_ALPHA * value + (1 - _LEVEL_ALPHA) * (level + trend)
        trend = _TREND_BETA * (level - previous_level) + (1 - _TREND_BETA) * trend

    return level, trend


def _confidence(values: list[float], slope: float) -> str:
    """How much the forecast deserves to be believed.

    Based on how noisy the series is relative to the movement being claimed. A trend
    smaller than the sample-to-sample jitter is not a trend, and labelling that
    honestly matters more than the number itself.
    """
    if len(values) < 2:
        return "low"

    jitter = sum(abs(b - a) for a, b in zip(values, values[1:])) / (len(values) - 1)

    if jitter == 0:
        return "high"
    ratio = abs(slope) / jitter

    if ratio >= 1.0:
        return "high"
    if ratio >= 0.35:
        return "medium"
    return "low"


def project(
    values: list[float],
    horizon: int = 10,
    threshold: float | None = None,
    direction: str = "above",
) -> Forecast:
    """Projects a series forward and estimates when it crosses a threshold.

    :param threshold: the line that matters — an SLO objective, a capacity limit.
    :param direction: whether crossing means going ``above`` the threshold or
        ``below`` it. Throughput and availability breach downwards; latency and
        error rate breach upwards.
    """
    if len(values) < MINIMUM_POINTS:
        return Forecast(
            level=values[-1] if values else 0.0,
            slope_per_step=0.0,
            projected=[],
            steps_to_breach=None,
            breaches=False,
            confidence="low",
            detail=f"need at least {MINIMUM_POINTS} points to fit a trend; got {len(values)}",
        )

    level, slope = _holt(values)
    projected = [round(level + slope * (step + 1), 3) for step in range(horizon)]
    confidence = _confidence(values, slope)

    steps_to_breach: int | None = None
    if threshold is not None:
        moving_towards = slope > 0 if direction == "above" else slope < 0
        already_past = level > threshold if direction == "above" else level < threshold

        if already_past:
            # Being past the line is an observation, not an extrapolation, so it is
            # reported regardless of how noisy the series is.
            steps_to_breach = 0
        elif moving_towards and slope != 0 and confidence != "low":
            steps_to_breach = max(1, int(round((threshold - level) / slope)))

    breaches = steps_to_breach is not None and steps_to_breach <= horizon

    if threshold is None:
        detail = f"level {level:.1f}, moving {slope:+.2f} per sample"
    elif steps_to_breach == 0:
        detail = f"already {direction} the {threshold:g} threshold at {level:.1f}"
    elif steps_to_breach is None and confidence == "low":
        # A slope smaller than the sample-to-sample jitter is noise, and dividing a
        # distance by it produces a number like "377 samples" that looks like a
        # forecast and is arithmetic on randomness. Refusing to give it is the
        # honest answer.
        detail = (
            f"level {level:.1f} with a {slope:+.2f} per-sample drift that is smaller "
            f"than the noise in the series; no breach estimate is meaningful"
        )
    elif steps_to_breach is None:
        detail = (
            f"level {level:.1f} moving {slope:+.2f} per sample; not heading "
            f"{direction} the {threshold:g} threshold"
        )
    else:
        detail = (
            f"level {level:.1f} moving {slope:+.2f} per sample; crosses {threshold:g} "
            f"in about {steps_to_breach} sample(s)"
        )

    return Forecast(
        level=round(level, 3),
        slope_per_step=round(slope, 4),
        projected=projected,
        steps_to_breach=steps_to_breach,
        breaches=breaches,
        confidence=confidence,
        detail=detail,
    )
