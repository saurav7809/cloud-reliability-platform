package io.aegiscloud.controlplane.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Populates a demo fleet on first boot.
 *
 * <p>Idempotent by design: if the organization row already exists the whole thing is
 * skipped, so restarting the stack never duplicates data or overwrites changes made
 * through the UI.
 *
 * <p>The fleet described here is representative sample data standing in for clusters
 * this installation does not have — three cloud providers' worth of production
 * targets. The local kind cluster is the one entry that corresponds to something
 * real on a developer machine, and the Deployment Engine reconciles it against the
 * live Kubernetes API once registered.
 */
@Component
public class DatabaseSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSeeder.class);

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final String adminEmail;
    private final String adminPassword;

    public DatabaseSeeder(JdbcTemplate jdbc,
                          PasswordEncoder encoder,
                          @Value("${AEGISCLOUD_ADMIN_EMAIL:admin@aegiscloud.local}") String adminEmail,
                          @Value("${AEGISCLOUD_ADMIN_PASSWORD:changeme123}") String adminPassword) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Integer existing = jdbc.queryForObject("SELECT count(*) FROM organization", Integer.class);
        if (existing != null && existing > 0) {
            log.info("seed skipped — database already populated");
            return;
        }

        String orgId = jdbc.queryForObject(
                "INSERT INTO organization (name) VALUES ('AegisCloud') RETURNING id::text", String.class);

        jdbc.update("INSERT INTO app_user (org_id, email, password_hash, role) VALUES (?::uuid,?,?,'ADMIN')",
                orgId, adminEmail, encoder.encode(adminPassword));

        seedSecondTenant();

        seedClusters(orgId);
        seedServices(orgId);
        seedTargets();
        seedSlos();
        seedControlPlaneHistory();
        seedAlerts();
        seedExperiments();

        log.info("seeded demo fleet: 4 clusters, 3 services, 6 targets");
    }

    /* ------------------------------- clusters ------------------------------- */

    /**
     * A second organisation with its own admin, one cluster and one service.
     *
     * <p>Exists so tenant isolation can be exercised rather than asserted. A
     * single-tenant database cannot demonstrate that a boundary holds: every query
     * returns the same rows whether or not the scoping works, so the tests pass
     * either way. Two tenants make a leak visible the moment it happens.
     *
     * <p>Deliberately small. It is a control, not a demo fleet.
     */
    private void seedSecondTenant() {
        String otherOrgId = jdbc.queryForObject(
                "INSERT INTO organization (name) VALUES ('Northwind Labs') RETURNING id::text",
                String.class);

        jdbc.update("INSERT INTO app_user (org_id, email, password_hash, role) "
                        + "VALUES (?::uuid,?,?,'ADMIN')",
                otherOrgId, "admin@northwind.local", encoder.encode("northwind123"));

        jdbc.update("""
                INSERT INTO cluster (org_id,name,provider_type,distribution,region,k8s_version,
                                     status,node_count,is_local)
                VALUES (?::uuid,'northwind-prod','GCP','GKE','europe-west4','v1.30.2',
                        'HEALTHY',3,false)
                """, otherOrgId);

        jdbc.update("""
                INSERT INTO service (org_id,name,owner_team,description)
                VALUES (?::uuid,'northwind-billing','Billing','Another tenant''s service')
                """, otherOrgId);

        log.info("seeded a second organisation so tenant isolation can be tested");
    }

    private void seedClusters(String orgId) {
        record ClusterSeed(String name, String provider, String distribution, String region,
                           String version, int nodes, String status, boolean local, String kubeContext,
                           int maxReplicas, int maxExperiments, String protectedNamespaces) {
        }

        // kubeContext names a real entry in the operator's kubeconfig, and is the
        // only thing that distinguishes one cloud from another at runtime.
        //
        // The local kind cluster gets the context kind creates. The three cloud
        // clusters get null: this installation genuinely holds no credentials for
        // them, and inventing a kubeconfig reference would make the Deployment
        // Engine report a connection failure for a cluster that was never
        // connectable in the first place. They are inventory rows until someone
        // registers a real cluster over them.
        ClusterSeed[] clusters = {
                new ClusterSeed("aegiscloud-local", "KIND", "kind", "local", "v1.37.0", 1, "HEALTHY", true,
                        "kind-aegiscloud-local", 3, 1, "[\"kube-system\",\"local-path-storage\"]"),
                new ClusterSeed("prod-eks-use1", "AWS", "EKS", "us-east-1", "v1.30.2", 6, "HEALTHY", false,
                        null, 20, 2, "[\"kube-system\",\"istio-system\"]"),
                new ClusterSeed("prod-gke-usc1", "GCP", "GKE", "us-central1", "v1.30.1", 5, "HEALTHY", false,
                        null, 15, 1, "[\"kube-system\"]"),
                new ClusterSeed("prod-aks-weu", "AZURE", "AKS", "westeurope", "v1.29.7", 4, "DEGRADED", false,
                        null, 12, 0, "[\"kube-system\",\"gatekeeper-system\"]"),
        };

        for (ClusterSeed c : clusters) {
            String clusterId = jdbc.queryForObject("""
                    INSERT INTO cluster (org_id,name,provider_type,distribution,region,k8s_version,
                                         node_count,status,is_local,kubeconfig_ref)
                    VALUES (?::uuid,?,?,?,?,?,?,?,?,?) RETURNING id::text
                    """, String.class,
                    orgId, c.name(), c.provider(), c.distribution(), c.region(), c.version(),
                    c.nodes(), c.status(), c.local(), c.kubeContext());

            jdbc.update("""
                    INSERT INTO policy (cluster_id,max_replicas,max_concurrent_experiments,protected_namespaces)
                    VALUES (?::uuid,?,?,?::jsonb)
                    """, clusterId, c.maxReplicas(), c.maxExperiments(), c.protectedNamespaces());
        }
    }

    /* ------------------------------- services ------------------------------- */

    private void seedServices(String orgId) {
        jdbc.update("""
                INSERT INTO service (org_id,name,owner_team,description,tags) VALUES
                  (?::uuid,'checkout-service','payments','Order checkout and payment capture','{"env":"prod","tier":"critical"}'::jsonb),
                  (?::uuid,'catalog-service','commerce','Product catalog and search','{"env":"prod","tier":"high"}'::jsonb),
                  (?::uuid,'auth-service','platform','Identity, sessions and token issuance','{"env":"prod","tier":"critical"}'::jsonb)
                """, orgId, orgId, orgId);

        // checkout and catalog both call auth. This is the shape that makes UC-4
        // — one cause, two symptoms — demonstrable once RCA lands.
        String[][] edges = {
                {"checkout-service", "auth-service"},
                {"catalog-service", "auth-service"},
                {"checkout-service", "catalog-service"},
        };
        for (String[] edge : edges) {
            jdbc.update("""
                    INSERT INTO service_dependency
                      (caller_service_id,callee_service_id,discovery_source,call_rate_per_min,error_rate_pct,latency_p95_ms)
                    VALUES ((SELECT id FROM service WHERE name = ?),
                            (SELECT id FROM service WHERE name = ?), 'TRACE', 420.0, 0.4, 62.0)
                    """, edge[0], edge[1]);
        }
    }

    /* -------------------------------- targets ------------------------------- */

    private void seedTargets() {
        record TargetSeed(String service, String cluster, String namespace, String label,
                          String strategy, String status, int replicas, int desired,
                          double score, double availability, double p95, double errorRate, double cost) {
        }

        TargetSeed[] targets = {
                new TargetSeed("checkout-service", "prod-eks-use1", "checkout", "prod-aws-use1", "CPU", "HEALTHY", 6, 6, 96.4, 99.94, 182.4, 0.06, 1284.50),
                new TargetSeed("checkout-service", "prod-gke-usc1", "checkout", "prod-gcp-usc1", "LATENCY", "HEALTHY", 5, 5, 91.2, 99.81, 241.7, 0.19, 1102.30),
                new TargetSeed("catalog-service", "prod-eks-use1", "catalog", "prod-aws-use1", "TREND", "HEALTHY", 4, 4, 98.1, 99.98, 94.2, 0.02, 742.10),
                new TargetSeed("catalog-service", "prod-aks-weu", "catalog", "prod-azure-weu", "CPU", "DEGRADED", 3, 5, 78.6, 99.12, 512.9, 0.88, 689.40),
                new TargetSeed("auth-service", "prod-eks-use1", "auth", "prod-aws-use1", "CPU", "HEALTHY", 8, 8, 99.2, 99.99, 41.8, 0.01, 1620.00),
                new TargetSeed("auth-service", "aegiscloud-local", "aegiscloud", "local-kind", "NONE", "HEALTHY", 1, 1, 94.0, 99.90, 12.4, 0.00, 0.00),
        };

        for (TargetSeed t : targets) {
            jdbc.update("""
                    INSERT INTO deployment_target
                      (service_id,cluster_id,namespace,label,scaling_strategy,deployment_status,
                       replicas,desired_replicas,reliability_score,availability_pct,latency_p95_ms,
                       error_rate_pct,monthly_cost_usd)
                    VALUES ((SELECT id FROM service WHERE name = ?),
                            (SELECT id FROM cluster WHERE name = ?),
                            ?,?,?,?,?,?,?,?,?,?,?)
                    """, t.service(), t.cluster(), t.namespace(), t.label(), t.strategy(), t.status(),
                    t.replicas(), t.desired(), t.score(), t.availability(), t.p95(), t.errorRate(), t.cost());
        }
    }

    /* --------------------------------- SLOs --------------------------------- */

    private void seedSlos() {
        record SloSeed(String service, String cluster, String sliType, double objective,
                       double current, double remaining, double burnRate) {
        }

        SloSeed[] slos = {
                new SloSeed("checkout-service", "prod-eks-use1", "AVAILABILITY", 99.9, 99.94, 62, 0.7),
                new SloSeed("checkout-service", "prod-eks-use1", "LATENCY_P95", 250, 182.4, 81, 0.4),
                new SloSeed("checkout-service", "prod-gke-usc1", "AVAILABILITY", 99.9, 99.81, 18, 2.4),
                new SloSeed("catalog-service", "prod-aks-weu", "AVAILABILITY", 99.9, 99.12, 0, 8.9),
                new SloSeed("auth-service", "prod-eks-use1", "AVAILABILITY", 99.95, 99.99, 91, 0.2),
        };

        for (SloSeed s : slos) {
            String sloId = jdbc.queryForObject("""
                    INSERT INTO slo (target_id,sli_type,objective_value,window_days)
                    VALUES (?::uuid,?,?,30) RETURNING id::text
                    """, String.class, targetId(s.service(), s.cluster()), s.sliType(), s.objective());

            jdbc.update("""
                    INSERT INTO error_budget_snapshot (slo_id,current_value,budget_remaining_pct,burn_rate)
                    VALUES (?::uuid,?,?,?)
                    """, sloId, s.current(), s.remaining(), s.burnRate());
        }
    }

    /* ---------------------------- control plane ----------------------------- */

    private void seedControlPlaneHistory() {
        record ScalingSeed(String service, String cluster, int previous, int next,
                           String metric, double value, String strategy, int minutesAgo) {
        }

        ScalingSeed[] scaling = {
                new ScalingSeed("auth-service", "prod-eks-use1", 6, 8, "CPU", 81.4, "CPU", 4),
                new ScalingSeed("checkout-service", "prod-gke-usc1", 4, 5, "LATENCY_P95", 268.2, "LATENCY", 17),
                new ScalingSeed("catalog-service", "prod-eks-use1", 5, 4, "TREND", -12.6, "TREND", 41),
                new ScalingSeed("checkout-service", "prod-eks-use1", 5, 6, "CPU", 76.9, "CPU", 68),
        };
        for (ScalingSeed s : scaling) {
            jdbc.update("""
                    INSERT INTO scaling_event
                      (target_id,previous_replicas,new_replicas,trigger_metric,trigger_value,strategy,decided_at)
                    VALUES (?::uuid,?,?,?,?,?,?)
                    """, targetId(s.service(), s.cluster()), s.previous(), s.next(),
                    s.metric(), s.value(), s.strategy(), minutesAgo(s.minutesAgo()));
        }

        record HealingSeed(String service, String cluster, String pod, String reason,
                           String action, int minutesAgo, boolean resolved) {
        }

        HealingSeed[] healing = {
                new HealingSeed("catalog-service", "prod-aks-weu", "catalog-7d9f4b-x2mq", "CRASH_LOOP", "RESTARTED", 9, false),
                new HealingSeed("catalog-service", "prod-aks-weu", "catalog-7d9f4b-k8tp", "OOM_KILLED", "RESCHEDULED", 25, true),
                new HealingSeed("checkout-service", "prod-gke-usc1", "checkout-5c8a1e-vv4d", "NOT_READY", "RESTARTED", 53, true),
        };
        for (HealingSeed h : healing) {
            Timestamp detected = minutesAgo(h.minutesAgo());
            Timestamp resolved = h.resolved()
                    ? Timestamp.from(detected.toInstant().plus(3, ChronoUnit.MINUTES))
                    : null;
            jdbc.update("""
                    INSERT INTO healing_event (target_id,pod_name,reason,action_taken,detected_at,resolved_at)
                    VALUES (?::uuid,?,?,?,?,?)
                    """, targetId(h.service(), h.cluster()), h.pod(), h.reason(), h.action(), detected, resolved);
        }
    }

    /* -------------------------------- alerts -------------------------------- */

    private void seedAlerts() {
        record AlertSeed(String service, String cluster, String severity, String status,
                         String message, int minutesAgo) {
        }

        AlertSeed[] alerts = {
                new AlertSeed("catalog-service", "prod-aks-weu", "CRITICAL", "OPEN",
                        "Error budget exhausted — burn rate 8.9x sustainable", 9),
                new AlertSeed("checkout-service", "prod-gke-usc1", "HIGH", "OPEN",
                        "Availability SLO burn rate 2.4x — 18% budget remaining", 31),
                new AlertSeed("catalog-service", "prod-aks-weu", "MEDIUM", "ACKNOWLEDGED",
                        "p95 latency 512.9ms exceeds 250ms objective", 120),
                new AlertSeed("checkout-service", "prod-eks-use1", "LOW", "RESOLVED",
                        "Transient replica shortfall during rollout", 360),
        };

        for (AlertSeed a : alerts) {
            jdbc.update("""
                    INSERT INTO alert (target_id,severity,status,message,opened_at)
                    VALUES (?::uuid,?,?,?,?)
                    """, targetId(a.service(), a.cluster()), a.severity(), a.status(),
                    a.message(), minutesAgo(a.minutesAgo()));
        }
    }

    /* ------------------------------ experiments ----------------------------- */

    private void seedExperiments() {
        record ExperimentSeed(String service, String targetService, String cluster, String fault,
                              String status, Double before, Double during, Double after,
                              int minutesAgo, boolean ended) {
        }

        ExperimentSeed[] experiments = {
                new ExperimentSeed("checkout-service", "checkout-service", "prod-eks-use1",
                        "LATENCY_INJECTION", "COMPLETED", 96.8, 71.2, 96.4, 192, true),
                new ExperimentSeed("auth-service", "auth-service", "prod-eks-use1",
                        "POD_KILL", "COMPLETED", 99.4, 88.1, 99.2, 1568, true),
                new ExperimentSeed("catalog-service", "catalog-service", "prod-aks-weu",
                        "RESOURCE_STARVATION", "REJECTED_BY_POLICY", null, null, null, 45, false),
                new ExperimentSeed("catalog-service", "catalog-service", "prod-eks-use1",
                        "NETWORK_PARTITION", "RUNNING", 98.1, 82.4, null, 6, false),
        };

        for (ExperimentSeed e : experiments) {
            Timestamp started = minutesAgo(e.minutesAgo());
            Timestamp ended = e.ended()
                    ? Timestamp.from(started.toInstant().plus(12, ChronoUnit.MINUTES))
                    : null;
            jdbc.update("""
                    INSERT INTO evaluation_run
                      (service_id,target_id,run_type,fault_spec,status,score_before,score_during,
                       score_after,started_at,ended_at)
                    VALUES ((SELECT id FROM service WHERE name = ?), ?::uuid, 'CHAOS', ?::jsonb, ?,
                            ?,?,?,?,?)
                    """, e.service(), targetId(e.targetService(), e.cluster()),
                    "{\"type\":\"" + e.fault() + "\",\"durationSeconds\":120}", e.status(),
                    e.before(), e.during(), e.after(), started, ended);
        }
    }

    /* -------------------------------- helpers ------------------------------- */

    /** Resolves a deployment target by the service/cluster pair that identifies it. */
    private String targetId(String service, String cluster) {
        return jdbc.queryForObject("""
                SELECT t.id::text FROM deployment_target t
                JOIN service sv ON sv.id = t.service_id
                JOIN cluster c  ON c.id  = t.cluster_id
                WHERE sv.name = ? AND c.name = ?
                """, String.class, service, cluster);
    }

    private static Timestamp minutesAgo(int minutes) {
        return Timestamp.from(Instant.now().minus(minutes, ChronoUnit.MINUTES));
    }
}
