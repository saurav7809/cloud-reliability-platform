package io.aegiscloud.controlplane.engine;

import io.aegiscloud.controlplane.k8s.WorkloadOperations;
import io.aegiscloud.controlplane.k8s.WorkloadOperations.PodObservation;
import io.aegiscloud.controlplane.k8s.WorkloadOperations.WorkloadObservation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;

/**
 * The autonomous loop: observe, conclude, check policy, act, verify.
 *
 * <p>Every cycle runs the same five steps for every target, and each step is recorded
 * whether or not the one after it happens. Two constraints from the architecture are
 * enforced here rather than trusted to callers:
 *
 * <ul>
 *   <li>Nothing reaches a cluster except through {@link PolicyEngine}, and only at
 *       autonomy level ACT. At SUGGEST the identical decision is recorded and stops.
 *   <li>Every applied action is verified on a later cycle against the reading that
 *       triggered it, and reversed when it made things worse. An action nobody checks
 *       is indistinguishable from a guess.
 * </ul>
 */
@Service
public class ReconciliationLoop {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationLoop.class);

    private final ControlPlaneStore store;
    private final PolicyEngine policy;
    private final WorkloadOperations workloads;
    private final ControlPlaneEvents events;

    private final Duration scalingCooldown;
    private final Duration verificationDelay;

    public ReconciliationLoop(ControlPlaneStore store, PolicyEngine policy,
                              WorkloadOperations workloads, ControlPlaneEvents events,
                              @Value("${aegiscloud.control-plane.scaling-cooldown-seconds:180}")
                              long scalingCooldownSeconds,
                              @Value("${aegiscloud.control-plane.verification-delay-seconds:60}")
                              long verificationDelaySeconds) {
        this.store = store;
        this.policy = policy;
        this.workloads = workloads;
        this.events = events;
        this.scalingCooldown = Duration.ofSeconds(scalingCooldownSeconds);
        this.verificationDelay = Duration.ofSeconds(verificationDelaySeconds);
    }

    /** What one pass over the fleet did, in the order it did it. */
    public record CycleReport(
            Instant ranAt,
            int targetsExamined,
            int targetsSkipped,
            List<String> decisions,
            int actionsApplied,
            int actionsSuggested,
            int actionsRejected,
            int outcomesVerified) {
    }

    /**
     * Runs on a timer, and is also what {@code POST /control-plane/reconcile} calls,
     * so an operator sees exactly the loop that runs unattended rather than a
     * separate code path that only resembles it.
     */
    @Scheduled(
            initialDelayString = "${aegiscloud.control-plane.initial-delay-ms:20000}",
            fixedDelayString = "${aegiscloud.control-plane.interval-ms:60000}")
    public void scheduledCycle() {
        try {
            CycleReport report = reconcile();
            if (report.actionsApplied() > 0 || report.actionsSuggested() > 0
                    || report.outcomesVerified() > 0) {
                log.info("control loop: {} examined, {} applied, {} suggested, {} rejected, {} verified",
                        report.targetsExamined(), report.actionsApplied(), report.actionsSuggested(),
                        report.actionsRejected(), report.outcomesVerified());
            }
        } catch (Exception e) {
            // The loop must survive a bad cycle. A control plane that stops
            // reconciling after one exception is worse than one that never ran.
            log.warn("control loop cycle failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Records a decision and pushes it to every connected dashboard at the moment it
     * is made, rather than leaving it to be discovered by the next poll.
     */
    private void note(List<String> decisions, String target, String decision) {
        decisions.add(target + ": " + decision);
        events.broadcast("decision", Map.of(
                "at", Instant.now().toString(),
                "target", target,
                "decision", decision));
    }

    /** One full pass over every target on a reachable cluster. */
    public CycleReport reconcile() {
        Instant startedAt = Instant.now();
        List<String> decisions = new ArrayList<>();
        events.broadcast("cycle-started", Map.of("at", startedAt.toString()));
        int applied = 0;
        int suggested = 0;
        int rejected = 0;
        int skipped = 0;

        int verified = verifyPending(decisions);

        List<ManagedTarget> targets = store.reachableTargets();
        for (ManagedTarget target : targets) {
            WorkloadObservation observation =
                    workloads.observe(target.kubeContext(), target.namespace(), target.workload());

            if (!observation.found()) {
                // A target row whose workload is not on the cluster is a fact worth
                // reporting, not something to act on: there is nothing to scale or
                // heal, and guessing would mean writing to a cluster about a
                // workload the platform cannot see.
                skipped++;
                note(decisions, target.label(), "skipped: " + observation.detail());
                continue;
            }

            store.updateReplicas(target.targetId(), observation.desiredReplicas());

            Tally healing = heal(target, observation, decisions);
            Tally scaling = scale(target, observation, decisions);

            applied += healing.applied() + scaling.applied();
            suggested += healing.suggested() + scaling.suggested();
            rejected += healing.rejected() + scaling.rejected();
        }

        CycleReport report = new CycleReport(startedAt, targets.size(), skipped, decisions,
                applied, suggested, rejected, verified);
        events.broadcast("cycle-finished", report);
        return report;
    }

    private record Tally(int applied, int suggested, int rejected) {
        static final Tally NONE = new Tally(0, 0, 0);

        Tally plus(Tally other) {
            return new Tally(applied + other.applied(), suggested + other.suggested(),
                    rejected + other.rejected());
        }
    }

    // ---------------------------------------------------------------- healing

    private Tally heal(ManagedTarget target, WorkloadObservation observation, List<String> decisions) {
        List<SelfHealingEngine.Diagnosis> diagnoses = SelfHealingEngine.diagnose(observation.pods());

        resolveRecovered(target, observation, diagnoses);

        if (diagnoses.isEmpty()) {
            return Tally.NONE;
        }

        Tally tally = Tally.NONE;
        for (SelfHealingEngine.Diagnosis diagnosis : diagnoses) {
            tally = tally.plus(actOnDiagnosis(target, observation, diagnosis, decisions));
        }
        return tally;
    }

    /**
     * Closes healing events for pods that are healthy again.
     *
     * <p>Resolution is decided from a later observation, not from the fact that a
     * delete call returned: a pod that has gone away has not recovered until its
     * replacement is ready, and a healing event closed on the strength of the action
     * alone would report success the platform never checked for.
     */
    private void resolveRecovered(ManagedTarget target, WorkloadObservation observation,
                                  List<SelfHealingEngine.Diagnosis> stillFailing) {
        List<String> open = store.openHealingPods(target.targetId());
        if (open.isEmpty()) {
            return;
        }
        List<String> failingNow = stillFailing.stream().map(SelfHealingEngine.Diagnosis::podName).toList();
        List<String> present = observation.pods().stream().map(PodObservation::name).toList();

        // Recovered means: no longer diagnosed as failing. A pod that was replaced no
        // longer exists at all, which counts — the failure it represented is over.
        List<String> recovered = open.stream()
                .filter(pod -> !failingNow.contains(pod))
                .filter(pod -> !present.contains(pod) || isReady(observation, pod))
                .toList();

        int closed = store.resolveHealingEvents(target.targetId(), recovered);
        if (closed > 0) {
            log.info("{}: {} healing event(s) resolved", target.label(), closed);
        }
    }

    private static boolean isReady(WorkloadObservation observation, String podName) {
        return observation.pods().stream()
                .filter(p -> p.name().equals(podName))
                .findFirst().map(PodObservation::ready).orElse(false);
    }

    private Tally actOnDiagnosis(ManagedTarget target, WorkloadObservation observation,
                                 SelfHealingEngine.Diagnosis diagnosis, List<String> decisions) {

        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("pod", diagnosis.podName());
        observed.put("failure", diagnosis.failure().name());
        observed.put("readyReplicas", observation.readyReplicas());
        observed.put("desiredReplicas", observation.desiredReplicas());

        // An escalation is a report, not an action: there is nothing to permit, so it
        // is recorded at any autonomy level and never touches the cluster.
        if (diagnosis.action() == ActionType.ESCALATE) {
            if (!store.openHealingPods(target.targetId()).contains(diagnosis.podName())) {
                store.recordHealingEvent(target.targetId(), diagnosis.podName(),
                        diagnosis.failure().name(), "ESCALATED");
                store.recordAction(target.targetId(), ActionType.ESCALATE, observed,
                        diagnosis.reason(), Map.of(), true, observation.readyPct());
            }
            note(decisions, target.label(), "escalated " + diagnosis.podName() + ": " + diagnosis.reason());
            return new Tally(0, 0, 0);
        }

        AutonomyLevel level = store.levelFor(target.clusterId(), ActionType.RESTART_POD);
        if (level == AutonomyLevel.OBSERVE) {
            note(decisions, target.label(), "observed " + diagnosis.podName() + ": "
                    + diagnosis.reason() + " (autonomy OBSERVE)");
            return Tally.NONE;
        }

        PolicyEngine.Decision permitted = policy.checkHeal(target);
        if (!permitted.allowed()) {
            store.recordAction(target.targetId(), ActionType.RESTART_POD, observed,
                    diagnosis.reason(), Map.of(), false, observation.readyPct());
            note(decisions, target.label(), "refused to replace " + diagnosis.podName()
                    + ": " + permitted.reason());
            return new Tally(0, 0, 1);
        }

        if (level == AutonomyLevel.SUGGEST) {
            store.recordAction(target.targetId(), ActionType.RESTART_POD, observed,
                    diagnosis.reason() + " — suggested only; autonomy for RESTART_POD is SUGGEST",
                    Map.of(), true, observation.readyPct());
            note(decisions, target.label(), "suggested replacing " + diagnosis.podName()
                    + ": " + diagnosis.reason());
            return new Tally(0, 1, 0);
        }

        Optional<String> failure =
                workloads.deletePod(target.kubeContext(), target.namespace(), diagnosis.podName());
        if (failure.isPresent()) {
            store.recordAction(target.targetId(), ActionType.RESTART_POD, observed,
                    diagnosis.reason() + " — delete failed: " + failure.get(), Map.of(), true,
                    observation.readyPct());
            note(decisions, target.label(), "failed to replace " + diagnosis.podName()
                    + ": " + failure.get());
            return Tally.NONE;
        }

        store.recordHealingEvent(target.targetId(), diagnosis.podName(),
                diagnosis.failure().name(), "RESTARTED");
        events.broadcast("healing", Map.of(
                "target", target.label(), "pod", diagnosis.podName(),
                "failure", diagnosis.failure().name(), "action", "RESTARTED"));
        store.recordAction(target.targetId(), ActionType.RESTART_POD, observed, diagnosis.reason(),
                Map.of("deletedPod", diagnosis.podName()), true, observation.readyPct());

        note(decisions, target.label(), "replaced " + diagnosis.podName() + ": " + diagnosis.reason());
        return new Tally(1, 0, 0);
    }

    // ---------------------------------------------------------------- scaling

    private Tally scale(ManagedTarget target, WorkloadObservation observation, List<String> decisions) {
        PolicyLimits limits = store.limitsFor(target.clusterId());

        List<Double> trend = store.recentLatency(target.targetId(), 12);
        OptionalDouble latestLatency = trend.isEmpty()
                ? OptionalDouble.empty() : OptionalDouble.of(trend.get(trend.size() - 1));

        ScalingEngine.Signals signals = new ScalingEngine.Signals(
                observation.cpuUtilizationPct(), latestLatency, target.latencyObjectiveMs(), trend);

        Duration sinceLastChange = store.lastScaledAt(target.targetId())
                .map(at -> Duration.between(at, Instant.now()))
                .orElse(null);

        ScalingEngine.Decision decision = ScalingEngine.decide(target.strategy(), signals,
                observation.desiredReplicas(), limits.maxReplicas(), sinceLastChange, scalingCooldown);

        if (!decision.act()) {
            // Held decisions are not written to the ledger — at one row per target
            // per minute the ledger would be nothing but "no change", and the
            // reasoning is available in the cycle report either way.
            note(decisions, target.label(), decision.reason());
            return Tally.NONE;
        }

        ActionType actionType = decision.actionType();
        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("strategy", target.strategy().name());
        observed.put("triggerMetric", decision.triggerMetric());
        observed.put("triggerValue", decision.triggerValue());
        observed.put("fromReplicas", decision.fromReplicas());
        observed.put("toReplicas", decision.toReplicas());

        AutonomyLevel level = store.levelFor(target.clusterId(), actionType);
        if (level == AutonomyLevel.OBSERVE) {
            note(decisions, target.label(), "observed: " + decision.reason() + " (autonomy OBSERVE)");
            return Tally.NONE;
        }

        PolicyEngine.Decision permitted = policy.checkScale(target, decision.toReplicas());
        if (!permitted.allowed()) {
            store.recordAction(target.targetId(), actionType, observed,
                    decision.reason() + " — " + permitted.reason(), Map.of(), false,
                    observation.readyPct());
            note(decisions, target.label(), permitted.reason());
            return new Tally(0, 0, 1);
        }

        if (level == AutonomyLevel.SUGGEST) {
            store.recordAction(target.targetId(), actionType, observed,
                    decision.reason() + " — suggested only; autonomy for " + actionType
                            + " is SUGGEST", Map.of(), true, observation.readyPct());
            note(decisions, target.label(), "suggested " + actionType + ": " + decision.reason());
            return new Tally(0, 1, 0);
        }

        Optional<String> failure = workloads.scale(target.kubeContext(), target.namespace(),
                target.workload(), decision.toReplicas());
        if (failure.isPresent()) {
            store.recordAction(target.targetId(), actionType, observed,
                    decision.reason() + " — scale failed: " + failure.get(), Map.of(), true,
                    observation.readyPct());
            note(decisions, target.label(), "scale failed: " + failure.get());
            return Tally.NONE;
        }

        store.recordScalingEvent(target.targetId(), decision.fromReplicas(), decision.toReplicas(),
                decision.triggerMetric(), decision.triggerValue(), target.strategy());
        events.broadcast("scaling", Map.of(
                "target", target.label(), "from", decision.fromReplicas(),
                "to", decision.toReplicas(), "trigger", decision.triggerMetric(),
                "value", decision.triggerValue()));
        store.updateReplicas(target.targetId(), decision.toReplicas());
        store.recordAction(target.targetId(), actionType, observed, decision.reason(),
                Map.of("fromReplicas", decision.fromReplicas(), "toReplicas", decision.toReplicas()),
                true, observation.readyPct());

        note(decisions, target.label(), actionType + " " + decision.fromReplicas() + " -> "
                + decision.toReplicas() + ": " + decision.reason());
        return new Tally(1, 0, 0);
    }

    // ----------------------------------------------------------- verification

    /**
     * Judges applied actions against the reading that triggered them, and reverses
     * the ones that made things worse.
     *
     * <p>The measure is the share of replicas that are ready — the one signal
     * available for every workload on every provider without extra instrumentation.
     * An action is given {@code verification-delay-seconds} to take effect before it
     * is judged, because a rollout in progress is not a regression.
     */
    private int verifyPending(List<String> decisions) {
        int verified = 0;

        for (ControlPlaneStore.PendingAction pending : store.pendingVerification()) {
            if (Duration.between(pending.executedAt(), Instant.now()).compareTo(verificationDelay) < 0) {
                continue;
            }

            Optional<ManagedTarget> maybeTarget = store.target(pending.targetId());
            if (maybeTarget.isEmpty()) {
                continue;
            }
            ManagedTarget target = maybeTarget.get();

            WorkloadObservation now =
                    workloads.observe(target.kubeContext(), target.namespace(), target.workload());
            if (!now.found()) {
                continue;
            }

            double before = pending.scoreBefore() == null ? 0 : pending.scoreBefore();
            double after = now.readyPct();
            String outcome = after > before + 1 ? "IMPROVED" : after < before - 1 ? "WORSENED" : "NO_CHANGE";

            if ("WORSENED".equals(outcome) && rollBack(target, pending)) {
                outcome = "ROLLED_BACK";
                note(decisions, target.label(), "rolled back " + pending.actionType()
                        + ": readiness fell from " + Math.round(before) + "% to " + Math.round(after) + "%");
            }

            store.recordOutcome(pending.id(), outcome, after);
            events.broadcast("outcome", Map.of(
                    "target", target.label(), "actionType", pending.actionType(),
                    "outcome", outcome, "scoreBefore", before, "scoreAfter", after));
            verified++;
        }

        return verified;
    }

    /**
     * Restores the replica count an action changed.
     *
     * <p>Only scaling is reversible in this sense: a deleted pod cannot be
     * un-deleted, so a RESTART_POD that did not help is recorded as WORSENED and left
     * for the next cycle to diagnose afresh rather than "rolled back" in name only.
     */
    private boolean rollBack(ManagedTarget target, ControlPlaneStore.PendingAction pending) {
        Object from = pending.executed().get("fromReplicas");
        if (!(from instanceof Number previous)) {
            return false;
        }
        Optional<String> failure = workloads.scale(target.kubeContext(), target.namespace(),
                target.workload(), previous.intValue());
        if (failure.isPresent()) {
            log.warn("{}: rollback to {} replicas failed: {}", target.label(), previous, failure.get());
            return false;
        }
        store.recordScalingEvent(target.targetId(), target.recordedReplicas(), previous.intValue(),
                "rollback", 0, target.strategy());
        store.updateReplicas(target.targetId(), previous.intValue());
        return true;
    }

    /** Verification of a single target, for callers that need the loop's view of one workload. */
    public WorkloadObservation observe(UUID targetId) {
        ManagedTarget target = store.target(targetId)
                .orElseThrow(() -> new IllegalArgumentException("no such deployment target: " + targetId));
        return workloads.observe(target.kubeContext(), target.namespace(), target.workload());
    }
}
