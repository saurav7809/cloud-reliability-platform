package io.aegiscloud.controlplane.optimize;

import io.aegiscloud.controlplane.engine.ControlPlaneEvents;
import io.aegiscloud.controlplane.engine.ControlPlaneStore;
import io.aegiscloud.controlplane.engine.ManagedTarget;
import io.aegiscloud.controlplane.engine.PolicyEngine;
import io.aegiscloud.controlplane.k8s.WorkloadOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;

/**
 * Produces recommendations, and applies the ones a person decides to take.
 *
 * <p>Gathering and applying live here; the judgement lives in the pure
 * {@link OptimizationAdvisor}. Applying is the interesting half, because it is the
 * one place in the Intelligence Layer that leads to a cluster write — and it does so
 * through the same Policy Engine the autonomous loop uses. A recommendation is
 * advice; taking it is an action, and actions are governed.
 */
@Service
public class OptimizationService {

    private static final Logger log = LoggerFactory.getLogger(OptimizationService.class);

    private final RecommendationStore store;
    private final ControlPlaneStore controlPlane;
    private final PolicyEngine policy;
    private final WorkloadOperations workloads;
    private final ControlPlaneEvents events;

    public OptimizationService(RecommendationStore store, ControlPlaneStore controlPlane,
                               PolicyEngine policy, WorkloadOperations workloads,
                               ControlPlaneEvents events) {
        this.store = store;
        this.controlPlane = controlPlane;
        this.policy = policy;
        this.workloads = workloads;
        this.events = events;
    }

    /** What one advisory pass produced. */
    public record AdvisoryReport(int targetsExamined, int recommendations, int withheld,
                                 double totalMonthlySavingUsd, List<String> findings) {
    }

    /**
     * Recommendations are refreshed on a slow timer.
     *
     * <p>Hourly rather than per-minute on purpose: advice that changes every cycle is
     * noise, and a recommendation an operator saw yesterday should still be there
     * today unless the facts behind it actually moved.
     */
    @Scheduled(
            initialDelayString = "${aegiscloud.optimization.initial-delay-ms:45000}",
            fixedDelayString = "${aegiscloud.optimization.interval-ms:3600000}")
    public void scheduledAdvisory() {
        // No caller means no organisation to inherit, so the timer iterates them
        // explicitly. Running as "the first organisation" would advise one tenant
        // and silently ignore every other.
        for (UUID orgId : store.organisationIds()) {
            try {
                AdvisoryReport report = advise(orgId);
                if (report.recommendations() > 0) {
                    log.info("optimization [{}]: {} recommendation(s) across {} target(s), ${} a month",
                            orgId, report.recommendations(), report.targetsExamined(),
                            Math.round(report.totalMonthlySavingUsd()));
                }
            } catch (Exception e) {
                log.warn("optimization pass failed for organisation {}: {}", orgId, e.getMessage(), e);
            }
        }
    }

    /** Examines every active target and refreshes the open recommendations. */
    public AdvisoryReport advise(UUID orgId) {
        List<String> findings = new ArrayList<>();
        List<RecommendationStore.TargetRow> targets = store.targetFacts(orgId);

        int produced = 0;
        int withheld = 0;
        double totalSaving = 0;

        for (RecommendationStore.TargetRow target : targets) {
            OptionalDouble utilisation = liveUtilisation(target);

            OptimizationAdvisor.TargetFacts facts = new OptimizationAdvisor.TargetFacts(
                    target.targetId(), target.serviceName(), target.clusterName(),
                    target.replicas(), utilisation, target.score(), target.budgetRemaining(),
                    target.monthlyCostUsd(), target.scalingStrategy(), target.hasLatencySlo(),
                    target.sampleCount());

            for (OptimizationAdvisor.Recommendation recommendation
                    : OptimizationAdvisor.advise(facts)) {

                store.upsert(recommendation);
                produced++;

                if (recommendation.safeToApply()) {
                    totalSaving += Math.max(0, recommendation.estimatedMonthlySavingUsd());
                } else {
                    withheld++;
                }

                findings.add(String.format("%s: %s%s", target.serviceName(),
                        recommendation.title(),
                        recommendation.safeToApply() ? "" : " (not offered as safe)"));
            }
        }

        AdvisoryReport report = new AdvisoryReport(targets.size(), produced, withheld,
                totalSaving, findings);

        events.broadcast("optimization", Map.of(
                "targets", targets.size(),
                "recommendations", produced,
                "withheld", withheld,
                "monthlySavingUsd", Math.round(totalSaving * 100) / 100.0));

        return report;
    }

    /**
     * Live CPU utilisation, which is the measurement most of the advice rests on.
     *
     * <p>Absent for a cluster the platform cannot reach or a workload with no CPU
     * request, and the advisor then says nothing about resources rather than
     * inferring them from the replica count.
     */
    private OptionalDouble liveUtilisation(RecommendationStore.TargetRow target) {
        if (target.kubeContext() == null || target.kubeContext().isBlank()) {
            return OptionalDouble.empty();
        }
        return workloads.observe(target.kubeContext(), target.namespace(), target.workload())
                .cpuUtilizationPct();
    }

    /** The result of acting on advice. */
    public record ApplyResult(String recommendationId, String status, String detail) {
    }

    /**
     * Applies a recommendation, subject to the Policy Engine (FR-34).
     *
     * <p>Three refusals stand between advice and a cluster write, and each one exists
     * because of a different way this could go wrong: the advisor's own safety
     * verdict, the recommendation still being open, and the same policy check that
     * governs autonomous scaling. A recommendation is not a licence — the operator
     * agreeing with it does not make it within policy.
     */
    public ApplyResult apply(UUID orgId, UUID recommendationId, UUID actor) {
        RecommendationStore.RecommendationRow recommendation = store.recommendation(orgId, recommendationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "no such recommendation: " + recommendationId));

        if (!"OPEN".equals(recommendation.status())) {
            throw new IllegalStateException("recommendation is already "
                    + recommendation.status().toLowerCase());
        }

        if (!recommendation.safeToApply()) {
            // Refusing rather than warning. This recommendation was recorded
            // precisely because acting on it would spend reliability the service
            // cannot spare, and a confirm-anyway path would make that a formality.
            throw new IllegalStateException("this recommendation is not offered as safe to apply: "
                    + recommendation.rationale());
        }

        Optional<Integer> proposedReplicas = recommendation.proposedReplicas();
        if (proposedReplicas.isEmpty()) {
            throw new IllegalStateException("this recommendation is advisory only and has no "
                    + "automatic action; it describes a change for a human to make");
        }

        UUID targetId = UUID.fromString(recommendation.targetId());
        ManagedTarget target = controlPlane.target(targetId)
                .orElseThrow(() -> new IllegalStateException("the target no longer exists"));

        PolicyEngine.Decision permitted = policy.checkScale(target, proposedReplicas.get());
        if (!permitted.allowed()) {
            store.close(recommendationId, "DISMISSED", actor,
                    "refused by policy: " + permitted.reason());
            return new ApplyResult(recommendationId.toString(), "DISMISSED",
                    "refused by policy: " + permitted.reason());
        }

        Optional<String> failure = workloads.scale(target.kubeContext(), target.namespace(),
                target.workload(), proposedReplicas.get());

        if (failure.isPresent()) {
            store.close(recommendationId, "OPEN", actor, "apply failed: " + failure.get());
            return new ApplyResult(recommendationId.toString(), "OPEN",
                    "could not apply: " + failure.get());
        }

        controlPlane.updateReplicas(targetId, proposedReplicas.get());
        controlPlane.recordScalingEvent(targetId, target.recordedReplicas(), proposedReplicas.get(),
                "recommendation", 0, target.strategy());

        String outcome = "applied: replicas set to " + proposedReplicas.get();
        store.close(recommendationId, "APPLIED", actor, outcome);

        log.info("recommendation {} applied to {}: {}", recommendationId, target.label(), outcome);
        events.broadcast("recommendation", Map.of(
                "target", target.label(), "status", "APPLIED",
                "detail", recommendation.title()));

        return new ApplyResult(recommendationId.toString(), "APPLIED", outcome);
    }

    /** Dismisses advice, with the reason kept so bad advice stays visible (FR-34). */
    public ApplyResult dismiss(UUID orgId, UUID recommendationId, UUID actor, String reason) {
        RecommendationStore.RecommendationRow recommendation = store.recommendation(orgId, recommendationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "no such recommendation: " + recommendationId));

        if (!"OPEN".equals(recommendation.status())) {
            throw new IllegalStateException("recommendation is already "
                    + recommendation.status().toLowerCase());
        }

        store.close(recommendationId, "DISMISSED", actor,
                reason == null || reason.isBlank() ? "dismissed without a reason" : reason);

        return new ApplyResult(recommendationId.toString(), "DISMISSED",
                "dismissed; the recommendation stays on record");
    }
}
