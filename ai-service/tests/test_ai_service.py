"""Tests for the statistics the platform will act on."""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app import anomaly, forecast, rca  # noqa: E402


# --------------------------------------------------------------------- anomaly

def test_a_short_series_yields_no_verdict():
    report = anomaly.detect([10, 11, 9])
    assert not report.found
    assert "at least" in report.detail


def test_a_steady_series_has_no_anomalies():
    report = anomaly.detect([100, 102, 99, 101, 98, 103, 100, 101, 99, 100])
    assert not report.found


def test_a_spike_is_detected_and_explained():
    report = anomaly.detect([100, 102, 99, 101, 98, 103, 100, 2000, 99, 100])
    assert report.found
    assert len(report.anomalies) == 1
    spike = report.anomalies[0]
    assert spike.value == 2000
    assert spike.direction == "above"
    assert "deviations above the median" in spike.detail


def test_the_spike_does_not_hide_itself():
    """The reason MAD is used rather than mean and standard deviation.

    One extreme point drags a mean upward and inflates a standard deviation enough
    that the point stops looking extreme. The median barely moves, so it does not.
    """
    with_spike = [100, 102, 99, 101, 98, 103, 100, 5000, 99, 100]
    report = anomaly.detect(with_spike)

    assert report.baseline == 100.0  # a mean here would be ~590
    assert report.anomalies[0].value == 5000


def test_a_drop_is_detected_as_below_baseline():
    report = anomaly.detect([100, 102, 99, 101, 98, 103, 100, 0, 99, 100])
    assert report.anomalies[0].direction == "below"


def test_a_constant_series_reports_differing_points_rather_than_dividing_by_zero():
    report = anomaly.detect([50, 50, 50, 50, 50, 50, 50, 77])
    assert report.found
    assert report.anomalies[0].value == 77
    assert "constant" in report.detail


def test_a_perfectly_constant_series_is_clean():
    report = anomaly.detect([50] * 12)
    assert not report.found


# -------------------------------------------------------------------- forecast

def test_a_short_series_is_not_forecast():
    result = forecast.project([1, 2])
    assert result.projected == []
    assert "at least" in result.detail


def test_a_rising_series_projects_upward():
    result = forecast.project([100, 110, 120, 130, 140, 150])
    assert result.slope_per_step > 5
    assert result.projected[0] > 150


def test_a_breach_is_estimated_in_samples():
    result = forecast.project(
        [100, 110, 120, 130, 140, 150], horizon=20, threshold=250, direction="above"
    )
    assert result.breaches
    assert result.steps_to_breach is not None
    assert 5 <= result.steps_to_breach <= 15
    assert "crosses 250" in result.detail


def test_a_flat_series_does_not_predict_a_breach():
    result = forecast.project(
        [100, 101, 99, 100, 102, 98, 100], horizon=20, threshold=250, direction="above"
    )
    assert not result.breaches
    assert result.steps_to_breach is None


def test_a_series_already_past_the_threshold_says_so():
    result = forecast.project(
        [300, 310, 305, 315, 320, 318], horizon=10, threshold=250, direction="above"
    )
    assert result.steps_to_breach == 0
    assert "already above" in result.detail


def test_downward_breaches_are_supported():
    """Availability and throughput breach by falling, not rising."""
    result = forecast.project(
        [99.9, 99.5, 99.0, 98.4, 97.9, 97.2], horizon=20, threshold=95, direction="below"
    )
    assert result.breaches
    assert result.steps_to_breach is not None


def test_a_trend_smaller_than_the_noise_is_reported_as_low_confidence():
    noisy = [100, 130, 70, 125, 75, 128, 72, 126]
    result = forecast.project(noisy)
    assert result.confidence == "low"


def test_a_clean_trend_is_reported_as_high_confidence():
    result = forecast.project([100, 120, 140, 160, 180, 200])
    assert result.confidence == "high"


# ------------------------------------------------------------------------- rca

def _candidate(name, confidence, **kwargs):
    return rca.Candidate(service_id=name, service_name=name, confidence=confidence, **kwargs)


def test_without_an_anomaly_score_the_platform_ranking_is_untouched():
    ranked = rca.rerank([_candidate("a", 0.6), _candidate("b", 0.4)])
    assert [item.candidate.service_name for item in ranked] == ["a", "b"]
    assert all(item.moved_by == 0 for item in ranked)


def test_unusual_telemetry_lifts_a_candidate():
    ranked = rca.rerank([
        _candidate("quiet", 0.50),
        _candidate("strange", 0.45, anomaly_score=9.0),
    ])
    assert ranked[0].candidate.service_name == "strange"
    assert ranked[0].moved_by > 0


def test_mild_deviation_does_not_move_anything():
    ranked = rca.rerank([_candidate("a", 0.5, anomaly_score=1.0)])
    assert ranked[0].moved_by == 0
    assert "within normal variation" in ranked[0].reasoning


def test_a_symptom_cannot_be_lifted_above_its_cause():
    """The constraint that keeps statistics from overruling topology."""
    ranked = rca.rerank([
        _candidate("cause", 0.60, upstream_of=2),
        _candidate("symptom", 0.50, downstream_of=1, anomaly_score=20.0),
    ])

    assert ranked[0].candidate.service_name == "cause"
    assert "downstream" in ranked[1].reasoning


def test_the_adjustment_is_bounded():
    ranked = rca.rerank([_candidate("a", 0.5, anomaly_score=10_000.0)])
    assert ranked[0].moved_by <= rca.MAX_ADJUSTMENT


def test_confidence_never_leaves_zero_to_one():
    ranked = rca.rerank([_candidate("a", 0.98, anomaly_score=50.0)])
    assert 0.0 <= ranked[0].adjusted_confidence <= 1.0


def test_no_candidate_is_invented():
    ranked = rca.rerank([_candidate("only", 0.5, anomaly_score=9.0)])
    assert len(ranked) == 1
    assert ranked[0].candidate.service_name == "only"
