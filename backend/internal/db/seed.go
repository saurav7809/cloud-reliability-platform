package db

import (
	"context"
	"fmt"
	"log"
	"time"

	"golang.org/x/crypto/bcrypt"
)

// Seed populates a demo fleet on first boot. It is idempotent: if the
// organization row already exists the whole thing is skipped, so restarting the
// stack never duplicates data or overwrites changes made through the UI.
//
// This is Phase 2 scaffolding. The fleet is representative sample data, not live
// cluster state — that arrives in Phase 3 with the client-go Deployment Engine.
func (d *DB) Seed(ctx context.Context, adminEmail, adminPassword string) error {
	var existing int
	if err := d.Pool.QueryRow(ctx, `SELECT count(*) FROM organization`).Scan(&existing); err != nil {
		return fmt.Errorf("check seed state: %w", err)
	}
	if existing > 0 {
		log.Printf("seed skipped — database already populated")
		return nil
	}

	tx, err := d.Pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	var orgID string
	if err := tx.QueryRow(ctx,
		`INSERT INTO organization (name) VALUES ('AegisCloud') RETURNING id`,
	).Scan(&orgID); err != nil {
		return fmt.Errorf("insert org: %w", err)
	}

	hash, err := bcrypt.GenerateFromPassword([]byte(adminPassword), bcrypt.DefaultCost)
	if err != nil {
		return err
	}
	if _, err := tx.Exec(ctx,
		`INSERT INTO app_user (org_id, email, password_hash, role) VALUES ($1,$2,$3,'ADMIN')`,
		orgID, adminEmail, string(hash),
	); err != nil {
		return fmt.Errorf("insert admin: %w", err)
	}

	// ------------------------------- clusters -------------------------------
	clusters := []struct {
		name, provider, dist, region, version string
		nodes                                 int
		status                                string
		local                                 bool
	}{
		{"aegiscloud-local", "KIND", "kind", "local", "v1.37.0", 1, "HEALTHY", true},
		{"prod-eks-use1", "AWS", "EKS", "us-east-1", "v1.30.2", 6, "HEALTHY", false},
		{"prod-gke-usc1", "GCP", "GKE", "us-central1", "v1.30.1", 5, "HEALTHY", false},
		{"prod-aks-weu", "AZURE", "AKS", "westeurope", "v1.29.7", 4, "DEGRADED", false},
	}
	clusterIDs := map[string]string{}
	for _, c := range clusters {
		var id string
		if err := tx.QueryRow(ctx,
			`INSERT INTO cluster (org_id,name,provider_type,distribution,region,k8s_version,node_count,status,is_local,kubeconfig_ref)
			 VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10) RETURNING id`,
			orgID, c.name, c.provider, c.dist, c.region, c.version, c.nodes, c.status, c.local,
			"kubeconfig-"+c.name,
		).Scan(&id); err != nil {
			return fmt.Errorf("insert cluster %s: %w", c.name, err)
		}
		clusterIDs[c.name] = id

		if _, err := tx.Exec(ctx,
			`INSERT INTO policy (cluster_id,max_replicas,max_concurrent_experiments,protected_namespaces)
			 VALUES ($1,$2,$3,$4)`,
			id, policyFor(c.name), experimentsFor(c.name), protectedFor(c.name),
		); err != nil {
			return fmt.Errorf("insert policy %s: %w", c.name, err)
		}
	}

	// ------------------------------- services -------------------------------
	services := []struct{ name, team, desc, tags string }{
		{"checkout-service", "payments", "Order checkout and payment capture",
			`{"env":"prod","tier":"critical"}`},
		{"catalog-service", "commerce", "Product catalog and search",
			`{"env":"prod","tier":"high"}`},
		{"auth-service", "platform", "Identity, sessions and token issuance",
			`{"env":"prod","tier":"critical"}`},
	}
	serviceIDs := map[string]string{}
	for _, s := range services {
		var id string
		if err := tx.QueryRow(ctx,
			`INSERT INTO service (org_id,name,owner_team,description,tags)
			 VALUES ($1,$2,$3,$4,$5::jsonb) RETURNING id`,
			orgID, s.name, s.team, s.desc, s.tags,
		).Scan(&id); err != nil {
			return fmt.Errorf("insert service %s: %w", s.name, err)
		}
		serviceIDs[s.name] = id
	}

	// Dependency edges — checkout and catalog both call auth. This is the shape
	// that makes UC-4 (one cause, two symptoms) demonstrable in Phase 7/8.
	deps := [][2]string{
		{"checkout-service", "auth-service"},
		{"catalog-service", "auth-service"},
		{"checkout-service", "catalog-service"},
	}
	for _, d2 := range deps {
		if _, err := tx.Exec(ctx,
			`INSERT INTO service_dependency (caller_service_id,callee_service_id,discovery_source,call_rate_per_min,error_rate_pct,latency_p95_ms)
			 VALUES ($1,$2,'TRACE',$3,$4,$5)`,
			serviceIDs[d2[0]], serviceIDs[d2[1]], 420.0, 0.4, 62.0,
		); err != nil {
			return fmt.Errorf("insert dependency: %w", err)
		}
	}

	// ------------------------------- targets --------------------------------
	targets := []struct {
		svc, cluster, ns, label, strategy, status string
		replicas, desired                        int
		score, avail, p95, errRate, cost         float64
	}{
		{"checkout-service", "prod-eks-use1", "checkout", "prod-aws-use1", "CPU", "HEALTHY", 6, 6, 96.4, 99.94, 182.4, 0.06, 1284.50},
		{"checkout-service", "prod-gke-usc1", "checkout", "prod-gcp-usc1", "LATENCY", "HEALTHY", 5, 5, 91.2, 99.81, 241.7, 0.19, 1102.30},
		{"catalog-service", "prod-eks-use1", "catalog", "prod-aws-use1", "TREND", "HEALTHY", 4, 4, 98.1, 99.98, 94.2, 0.02, 742.10},
		{"catalog-service", "prod-aks-weu", "catalog", "prod-azure-weu", "CPU", "DEGRADED", 3, 5, 78.6, 99.12, 512.9, 0.88, 689.40},
		{"auth-service", "prod-eks-use1", "auth", "prod-aws-use1", "CPU", "HEALTHY", 8, 8, 99.2, 99.99, 41.8, 0.01, 1620.00},
		{"auth-service", "aegiscloud-local", "aegiscloud", "local-kind", "NONE", "HEALTHY", 1, 1, 94.0, 99.90, 12.4, 0.00, 0.00},
	}
	targetIDs := make([]string, 0, len(targets))
	for _, t := range targets {
		var id string
		if err := tx.QueryRow(ctx,
			`INSERT INTO deployment_target
			 (service_id,cluster_id,namespace,label,scaling_strategy,deployment_status,replicas,desired_replicas,
			  reliability_score,availability_pct,latency_p95_ms,error_rate_pct,monthly_cost_usd)
			 VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13) RETURNING id`,
			serviceIDs[t.svc], clusterIDs[t.cluster], t.ns, t.label, t.strategy, t.status,
			t.replicas, t.desired, t.score, t.avail, t.p95, t.errRate, t.cost,
		).Scan(&id); err != nil {
			return fmt.Errorf("insert target %s/%s: %w", t.svc, t.cluster, err)
		}
		targetIDs = append(targetIDs, id)
	}

	// --------------------------------- SLOs ---------------------------------
	slos := []struct {
		targetIdx  int
		sli        string
		objective  float64
		current    float64
		remaining  float64
		burn       float64
	}{
		{0, "AVAILABILITY", 99.9, 99.94, 62, 0.7},
		{0, "LATENCY_P95", 250, 182.4, 81, 0.4},
		{1, "AVAILABILITY", 99.9, 99.81, 18, 2.4},
		{3, "AVAILABILITY", 99.9, 99.12, 0, 8.9},
		{4, "AVAILABILITY", 99.95, 99.99, 91, 0.2},
	}
	for _, s := range slos {
		var sloID string
		if err := tx.QueryRow(ctx,
			`INSERT INTO slo (target_id,sli_type,objective_value,window_days) VALUES ($1,$2,$3,30) RETURNING id`,
			targetIDs[s.targetIdx], s.sli, s.objective,
		).Scan(&sloID); err != nil {
			return fmt.Errorf("insert slo: %w", err)
		}
		if _, err := tx.Exec(ctx,
			`INSERT INTO error_budget_snapshot (slo_id,current_value,budget_remaining_pct,burn_rate)
			 VALUES ($1,$2,$3,$4)`,
			sloID, s.current, s.remaining, s.burn,
		); err != nil {
			return fmt.Errorf("insert budget: %w", err)
		}
	}

	// ---------------------------- control plane -----------------------------
	now := time.Now().UTC()
	scaling := []struct {
		idx           int
		prev, next    int
		metric        string
		value         float64
		strategy      string
		minutesAgo    int
	}{
		{4, 6, 8, "CPU", 81.4, "CPU", 4},
		{1, 4, 5, "LATENCY_P95", 268.2, "LATENCY", 17},
		{2, 5, 4, "TREND", -12.6, "TREND", 41},
		{0, 5, 6, "CPU", 76.9, "CPU", 68},
	}
	for _, s := range scaling {
		if _, err := tx.Exec(ctx,
			`INSERT INTO scaling_event (target_id,previous_replicas,new_replicas,trigger_metric,trigger_value,strategy,decided_at)
			 VALUES ($1,$2,$3,$4,$5,$6,$7)`,
			targetIDs[s.idx], s.prev, s.next, s.metric, s.value, s.strategy,
			now.Add(-time.Duration(s.minutesAgo)*time.Minute),
		); err != nil {
			return fmt.Errorf("insert scaling event: %w", err)
		}
	}

	healing := []struct {
		idx           int
		pod, reason   string
		action        string
		minutesAgo    int
		resolved      bool
	}{
		{3, "catalog-7d9f4b-x2mq", "CRASH_LOOP", "RESTARTED", 9, false},
		{3, "catalog-7d9f4b-k8tp", "OOM_KILLED", "RESCHEDULED", 25, true},
		{1, "checkout-5c8a1e-vv4d", "NOT_READY", "RESTARTED", 53, true},
	}
	for _, h := range healing {
		detected := now.Add(-time.Duration(h.minutesAgo) * time.Minute)
		var resolved any
		if h.resolved {
			resolved = detected.Add(3 * time.Minute)
		}
		if _, err := tx.Exec(ctx,
			`INSERT INTO healing_event (target_id,pod_name,reason,action_taken,detected_at,resolved_at)
			 VALUES ($1,$2,$3,$4,$5,$6)`,
			targetIDs[h.idx], h.pod, h.reason, h.action, detected, resolved,
		); err != nil {
			return fmt.Errorf("insert healing event: %w", err)
		}
	}

	// -------------------------------- alerts --------------------------------
	alerts := []struct {
		idx              int
		severity, status string
		message          string
		minutesAgo       int
	}{
		{3, "CRITICAL", "OPEN", "Error budget exhausted — burn rate 8.9x sustainable", 9},
		{1, "HIGH", "OPEN", "Availability SLO burn rate 2.4x — 18% budget remaining", 31},
		{3, "MEDIUM", "ACKNOWLEDGED", "p95 latency 512.9ms exceeds 250ms objective", 120},
		{0, "LOW", "RESOLVED", "Transient replica shortfall during rollout", 360},
	}
	for _, a := range alerts {
		if _, err := tx.Exec(ctx,
			`INSERT INTO alert (target_id,severity,status,message,opened_at) VALUES ($1,$2,$3,$4,$5)`,
			targetIDs[a.idx], a.severity, a.status, a.message,
			now.Add(-time.Duration(a.minutesAgo)*time.Minute),
		); err != nil {
			return fmt.Errorf("insert alert: %w", err)
		}
	}

	// ------------------------------ experiments -----------------------------
	experiments := []struct {
		svcIdx                int
		targetIdx             int
		fault, status         string
		before, during, after float64
		minutesAgo            int
		ended                 bool
	}{
		{0, 0, "LATENCY_INJECTION", "COMPLETED", 96.8, 71.2, 96.4, 192, true},
		{2, 4, "POD_KILL", "COMPLETED", 99.4, 88.1, 99.2, 1568, true},
		{1, 3, "RESOURCE_STARVATION", "REJECTED_BY_POLICY", 0, 0, 0, 45, false},
		{1, 2, "NETWORK_PARTITION", "RUNNING", 98.1, 82.4, 0, 6, false},
	}
	svcNames := []string{"checkout-service", "catalog-service", "auth-service"}
	for _, e := range experiments {
		started := now.Add(-time.Duration(e.minutesAgo) * time.Minute)
		var ended any
		if e.ended {
			ended = started.Add(12 * time.Minute)
		}
		spec := fmt.Sprintf(`{"type":%q,"durationSeconds":120}`, e.fault)
		if _, err := tx.Exec(ctx,
			`INSERT INTO evaluation_run (service_id,target_id,run_type,fault_spec,status,score_before,score_during,score_after,started_at,ended_at)
			 VALUES ($1,$2,'CHAOS',$3::jsonb,$4,$5,$6,$7,$8,$9)`,
			serviceIDs[svcNames[e.svcIdx]], targetIDs[e.targetIdx], spec, e.status,
			nullZero(e.before), nullZero(e.during), nullZero(e.after), started, ended,
		); err != nil {
			return fmt.Errorf("insert experiment: %w", err)
		}
	}

	if err := tx.Commit(ctx); err != nil {
		return fmt.Errorf("commit seed: %w", err)
	}

	log.Printf("seeded demo fleet: %d clusters, %d services, %d targets",
		len(clusters), len(services), len(targets))
	return nil
}

func nullZero(v float64) any {
	if v == 0 {
		return nil
	}
	return v
}

func policyFor(cluster string) int {
	switch cluster {
	case "prod-eks-use1":
		return 20
	case "prod-gke-usc1":
		return 15
	case "prod-aks-weu":
		return 12
	default:
		return 3
	}
}

func experimentsFor(cluster string) int {
	switch cluster {
	case "prod-eks-use1":
		return 2
	case "prod-aks-weu":
		return 0 // degraded cluster: fault injection blocked entirely
	default:
		return 1
	}
}

func protectedFor(cluster string) string {
	switch cluster {
	case "prod-eks-use1":
		return `["kube-system","istio-system"]`
	case "prod-aks-weu":
		return `["kube-system","gatekeeper-system"]`
	case "aegiscloud-local":
		return `["kube-system","local-path-storage"]`
	default:
		return `["kube-system"]`
	}
}
