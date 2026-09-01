/*
 * AegisCloud's provider-neutral core types.
 *
 * Nothing in this package imports a cloud SDK — an AWS EKS cluster, an Azure AKS
 * cluster, a GCP GKE cluster and a local kind cluster are all just a Cluster with a
 * different ProviderType. That is the structural guarantee behind "cloud-agnostic";
 * see docs/phase-1-architecture/02-architecture.md.
 *
 * Field names here are the wire contract consumed by web/. They intentionally
 * match the JSON tags of the Go implementation these records replace, so the
 * dashboard required no changes when the control plane moved to Spring Boot.
 */
package io.aegiscloud.controlplane.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class Models {

    private Models() {
    }

    public enum ProviderType {
        AWS, GCP, AZURE, KIND, ON_PREM
    }

    public enum ClusterStatus {
        HEALTHY, DEGRADED, UNREACHABLE
    }

    public enum DeploymentStatus {
        HEALTHY, DEGRADED, DEPLOYING, FAILED
    }

    public enum ScalingStrategy {
        CPU, LATENCY, TREND, NONE
    }

    /**
     * The SLI types the schema permits.
     *
     * <p>Kept in step with the CHECK constraint on {@code slo.sli_type}: the database
     * accepts all five, so a narrower enum here would fail to read a row the database
     * was happy to store.
     */
    public enum SliType {
        AVAILABILITY, LATENCY_P95, LATENCY_P99, ERROR_RATE, THROUGHPUT
    }

    public enum AlertSeverity {
        LOW, MEDIUM, HIGH, CRITICAL
    }

    public enum AlertStatus {
        OPEN, ACKNOWLEDGED, RESOLVED
    }

    public enum RunType {
        SCHEDULED_PROBE, CHAOS, MANUAL
    }

    public enum RunStatus {
        RUNNING, COMPLETED, FAILED, ABORTED, REJECTED_BY_POLICY
    }

    /**
     * A registered Kubernetes cluster. {@code distribution} (EKS/AKS/GKE/kind) is
     * descriptive metadata only — it never changes control flow.
     */
    public record Cluster(
            String id,
            String name,
            ProviderType provider,
            String distribution,
            String region,
            ClusterStatus status,
            int nodeCount,
            String k8sVersion,
            boolean isLocal) {
    }

    public record Service(
            String id,
            String name,
            String ownerTeam,
            String description,
            Map<String, String> tags) {
    }

    /**
     * One service running on one cluster — the unit AegisCloud scales, heals,
     * evaluates and scores.
     */
    public record DeploymentTarget(
            String id,
            String serviceId,
            String serviceName,
            String clusterId,
            String clusterName,
            ProviderType provider,
            String region,
            String namespace,
            ScalingStrategy scalingStrategy,
            DeploymentStatus status,
            int replicas,
            int desiredReplicas,
            double reliabilityScore,
            double availabilityPct,
            double latencyP95Ms,
            double errorRatePct,
            double monthlyCostUsd) {
    }

    public record Slo(
            String id,
            String targetId,
            String targetLabel,
            SliType sliType,
            double objectiveValue,
            int windowDays,
            double currentValue,
            double budgetRemainingPct,
            double burnRate) {
    }

    public record ScalingEvent(
            String id,
            String targetId,
            String targetLabel,
            int previousReplicas,
            int newReplicas,
            String triggerMetric,
            double triggerValue,
            ScalingStrategy strategy,
            Instant decidedAt) {
    }

    public record HealingEvent(
            String id,
            String targetId,
            String targetLabel,
            String podName,
            String reason,
            String actionTaken,
            Instant detectedAt,
            Instant resolvedAt) {
    }

    public record Alert(
            String id,
            String targetId,
            String targetLabel,
            AlertSeverity severity,
            AlertStatus status,
            String message,
            Instant openedAt) {
    }

    public record ExperimentRun(
            String id,
            String serviceName,
            String targetLabel,
            RunType runType,
            String faultType,
            RunStatus status,
            double scoreBefore,
            double scoreDuring,
            double scoreAfter,
            Instant startedAt,
            Instant endedAt) {
    }

    /**
     * Guardrails the Policy Engine enforces before Auto-Scaling, Self-Healing or
     * the Experiment Engine act.
     */
    public record Policy(
            String id,
            String clusterId,
            String clusterName,
            int maxReplicas,
            int maxConcurrentExperiments,
            List<String> protectedNamespaces) {
    }

    public record ProviderScore(
            ProviderType provider,
            double score,
            int targets,
            double costUsd) {
    }

    public record ScorePoint(
            String date,
            double score) {
    }

    public record EngineStatus(
            String name,
            String status,
            String detail,
            int actionsLast24h) {
    }

    public record ObservabilitySource(
            String name,
            String kind,
            String status,
            String ingestRate) {
    }

    /**
     * The dashboard summary rollup. {@code cacheHit} reports whether this was
     * served from Redis rather than recomputed — surfaced so the cache can be
     * observed instead of assumed.
     */
    public record Overview(
            int totalClusters,
            int healthyClusters,
            int totalServices,
            int totalTargets,
            int totalReplicas,
            int openAlerts,
            double avgScore,
            double monthlyCostUsd,
            List<ProviderScore> scoreByProvider,
            List<ScorePoint> scoreTrend,
            List<ScalingEvent> recentScaling,
            List<HealingEvent> recentHealing,
            List<EngineStatus> engineStatus,
            List<ObservabilitySource> observabilityFeed,
            boolean cacheHit) {

        /** Returns a copy with the cache-hit flag set, leaving everything else intact. */
        public Overview withCacheHit(boolean hit) {
            return new Overview(totalClusters, healthyClusters, totalServices, totalTargets,
                    totalReplicas, openAlerts, avgScore, monthlyCostUsd, scoreByProvider,
                    scoreTrend, recentScaling, recentHealing, engineStatus, observabilityFeed, hit);
        }
    }
}
