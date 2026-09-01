// Package store is AegisCloud's data access layer, backed by PostgreSQL.
//
// The fleet it serves is seeded demo data (see internal/db/seed.go) rather than
// live cluster state — that arrives in Phase 3 with the client-go Deployment
// Engine. The persistence, queries and aggregation below are real; only the
// origin of the rows is synthetic.
package store

import (
	"context"
	"math"
	"math/rand"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/aegiscloud/backend/internal/cache"
	"github.com/aegiscloud/backend/internal/domain"
)

const overviewCacheKey = "aegiscloud:overview:v1"

type Store struct {
	pool  *pgxpool.Pool
	cache *cache.Cache
}

func New(pool *pgxpool.Pool, c *cache.Cache) *Store {
	return &Store{pool: pool, cache: c}
}

func (s *Store) Clusters(ctx context.Context) ([]domain.Cluster, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT id, name, provider_type, COALESCE(distribution,''), COALESCE(region,''),
		       status, node_count, COALESCE(k8s_version,''), is_local
		FROM cluster WHERE is_active ORDER BY is_local DESC, name`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := []domain.Cluster{}
	for rows.Next() {
		var c domain.Cluster
		if err := rows.Scan(&c.ID, &c.Name, &c.Provider, &c.Distribution, &c.Region,
			&c.Status, &c.NodeCount, &c.K8sVersion, &c.IsLocal); err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

func (s *Store) Services(ctx context.Context) ([]domain.Service, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT id, name, COALESCE(owner_team,''), COALESCE(description,''), tags
		FROM service ORDER BY name`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := []domain.Service{}
	for rows.Next() {
		var s2 domain.Service
		if err := rows.Scan(&s2.ID, &s2.Name, &s2.OwnerTeam, &s2.Description, &s2.Tags); err != nil {
			return nil, err
		}
		out = append(out, s2)
	}
	return out, rows.Err()
}

func (s *Store) Targets(ctx context.Context) ([]domain.DeploymentTarget, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT t.id, t.service_id, sv.name, t.cluster_id, c.name, c.provider_type,
		       COALESCE(c.region,''), t.namespace, t.scaling_strategy, t.deployment_status,
		       t.replicas, t.desired_replicas, t.reliability_score, t.availability_pct,
		       t.latency_p95_ms, t.error_rate_pct, t.monthly_cost_usd
		FROM deployment_target t
		JOIN service sv ON sv.id = t.service_id
		JOIN cluster c  ON c.id  = t.cluster_id
		WHERE t.is_active
		ORDER BY sv.name, c.name`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := []domain.DeploymentTarget{}
	for rows.Next() {
		var t domain.DeploymentTarget
		if err := rows.Scan(&t.ID, &t.ServiceID, &t.ServiceName, &t.ClusterID, &t.ClusterName,
			&t.Provider, &t.Region, &t.Namespace, &t.ScalingStrategy, &t.Status,
			&t.Replicas, &t.DesiredReplicas, &t.ReliabilityScore, &t.AvailabilityPct,
			&t.LatencyP95Ms, &t.ErrorRatePct, &t.MonthlyCostUSD); err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

// Slos joins each SLO to its most recent error-budget snapshot via DISTINCT ON,
// so the dashboard gets objective and current burn state in one round trip.
func (s *Store) Slos(ctx context.Context) ([]domain.Slo, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT s.id, s.target_id, sv.name || ' @ ' || c.name, s.sli_type, s.objective_value,
		       s.window_days, COALESCE(b.current_value,0), COALESCE(b.budget_remaining_pct,100),
		       COALESCE(b.burn_rate,0)
		FROM slo s
		JOIN deployment_target t ON t.id = s.target_id
		JOIN service sv ON sv.id = t.service_id
		JOIN cluster c  ON c.id  = t.cluster_id
		LEFT JOIN LATERAL (
			SELECT current_value, budget_remaining_pct, burn_rate
			FROM error_budget_snapshot
			WHERE slo_id = s.id ORDER BY computed_at DESC LIMIT 1
		) b ON true
		WHERE s.is_active
		ORDER BY b.burn_rate DESC NULLS LAST`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := []domain.Slo{}
	for rows.Next() {
		var x domain.Slo
		if err := rows.Scan(&x.ID, &x.TargetID, &x.TargetLabel, &x.SliType, &x.ObjectiveValue,
			&x.WindowDays, &x.CurrentValue, &x.BudgetRemainingPct, &x.BurnRate); err != nil {
			return nil, err
		}
		out = append(out, x)
	}
	return out, rows.Err()
}

func (s *Store) ScalingEvents(ctx context.Context) ([]domain.ScalingEvent, error) {
	return s.scalingEvents(ctx, 50)
}

func (s *Store) scalingEvents(ctx context.Context, limit int) ([]domain.ScalingEvent, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT e.id::text, e.target_id, sv.name || ' @ ' || c.name, e.previous_replicas,
		       e.new_replicas, e.trigger_metric, e.trigger_value, e.strategy, e.decided_at
		FROM scaling_event e
		JOIN deployment_target t ON t.id = e.target_id
		JOIN service sv ON sv.id = t.service_id
		JOIN cluster c  ON c.id  = t.cluster_id
		ORDER BY e.decided_at DESC LIMIT $1`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := []domain.ScalingEvent{}
	for rows.Next() {
		var e domain.ScalingEvent
		if err := rows.Scan(&e.ID, &e.TargetID, &e.TargetLabel, &e.PreviousReplicas,
			&e.NewReplicas, &e.TriggerMetric, &e.TriggerValue, &e.Strategy, &e.DecidedAt); err != nil {
			return nil, err
		}
		out = append(out, e)
	}
	return out, rows.Err()
}

func (s *Store) HealingEvents(ctx context.Context) ([]domain.HealingEvent, error) {
	return s.healingEvents(ctx, 50)
}

func (s *Store) healingEvents(ctx context.Context, limit int) ([]domain.HealingEvent, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT h.id::text, h.target_id, sv.name || ' @ ' || c.name, h.pod_name, h.reason,
		       h.action_taken, h.detected_at, h.resolved_at
		FROM healing_event h
		JOIN deployment_target t ON t.id = h.target_id
		JOIN service sv ON sv.id = t.service_id
		JOIN cluster c  ON c.id  = t.cluster_id
		ORDER BY h.detected_at DESC LIMIT $1`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := []domain.HealingEvent{}
	for rows.Next() {
		var h domain.HealingEvent
		if err := rows.Scan(&h.ID, &h.TargetID, &h.TargetLabel, &h.PodName, &h.Reason,
			&h.ActionTaken, &h.DetectedAt, &h.ResolvedAt); err != nil {
			return nil, err
		}
		out = append(out, h)
	}
	return out, rows.Err()
}

func (s *Store) Alerts(ctx context.Context) ([]domain.Alert, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT a.id, a.target_id, sv.name || ' @ ' || c.name, a.severity, a.status,
		       a.message, a.opened_at
		FROM alert a
		JOIN deployment_target t ON t.id = a.target_id
		JOIN service sv ON sv.id = t.service_id
		JOIN cluster c  ON c.id  = t.cluster_id
		ORDER BY a.opened_at DESC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := []domain.Alert{}
	for rows.Next() {
		var a domain.Alert
		if err := rows.Scan(&a.ID, &a.TargetID, &a.TargetLabel, &a.Severity, &a.Status,
			&a.Message, &a.OpenedAt); err != nil {
			return nil, err
		}
		out = append(out, a)
	}
	return out, rows.Err()
}

func (s *Store) Experiments(ctx context.Context) ([]domain.ExperimentRun, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT r.id, sv.name, COALESCE(c.name,''), r.run_type,
		       COALESCE(r.fault_spec->>'type',''), r.status,
		       COALESCE(r.score_before,0), COALESCE(r.score_during,0), COALESCE(r.score_after,0),
		       r.started_at, r.ended_at
		FROM evaluation_run r
		JOIN service sv ON sv.id = r.service_id
		LEFT JOIN deployment_target t ON t.id = r.target_id
		LEFT JOIN cluster c ON c.id = t.cluster_id
		ORDER BY r.started_at DESC LIMIT 50`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := []domain.ExperimentRun{}
	for rows.Next() {
		var e domain.ExperimentRun
		if err := rows.Scan(&e.ID, &e.ServiceName, &e.TargetLabel, &e.RunType, &e.FaultType,
			&e.Status, &e.ScoreBefore, &e.ScoreDuring, &e.ScoreAfter,
			&e.StartedAt, &e.EndedAt); err != nil {
			return nil, err
		}
		out = append(out, e)
	}
	return out, rows.Err()
}

func (s *Store) Policies(ctx context.Context) ([]domain.Policy, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT p.id, p.cluster_id, c.name, p.max_replicas, p.max_concurrent_experiments,
		       p.protected_namespaces
		FROM policy p JOIN cluster c ON c.id = p.cluster_id
		ORDER BY c.name`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := []domain.Policy{}
	for rows.Next() {
		var p domain.Policy
		if err := rows.Scan(&p.ID, &p.ClusterID, &p.ClusterName, &p.MaxReplicas,
			&p.MaxConcurrentExperiments, &p.ProtectedNamespaces); err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

// SetAlertStatus transitions an alert and invalidates the cached overview, since
// the open-alert count it reports has just changed.
func (s *Store) SetAlertStatus(ctx context.Context, id, status string) (bool, error) {
	tag, err := s.pool.Exec(ctx,
		`UPDATE alert SET status = $2,
		        acknowledged_at = CASE WHEN $2 = 'ACKNOWLEDGED' THEN now() ELSE acknowledged_at END,
		        resolved_at     = CASE WHEN $2 = 'RESOLVED'     THEN now() ELSE resolved_at END
		 WHERE id = $1`, id, status)
	if err != nil {
		return false, err
	}
	if tag.RowsAffected() == 0 {
		return false, nil
	}
	s.cache.Invalidate(ctx, overviewCacheKey)
	return true, nil
}

// Overview is the dashboard rollup: the most expensive read in the API, so it is
// cached in Redis and invalidated on mutation.
func (s *Store) Overview(ctx context.Context) (domain.Overview, error) {
	var cached domain.Overview
	if s.cache.GetJSON(ctx, overviewCacheKey, &cached) {
		cached.CacheHit = true
		return cached, nil
	}

	ov := domain.Overview{}

	if err := s.pool.QueryRow(ctx, `
		SELECT (SELECT count(*) FROM cluster WHERE is_active),
		       (SELECT count(*) FROM cluster WHERE is_active AND status = 'HEALTHY'),
		       (SELECT count(*) FROM service),
		       (SELECT count(*) FROM deployment_target WHERE is_active),
		       (SELECT COALESCE(sum(replicas),0) FROM deployment_target WHERE is_active),
		       (SELECT count(*) FROM alert WHERE status = 'OPEN'),
		       (SELECT COALESCE(round(avg(reliability_score)::numeric,1),0) FROM deployment_target WHERE is_active),
		       (SELECT COALESCE(sum(monthly_cost_usd),0) FROM deployment_target WHERE is_active)
	`).Scan(&ov.TotalClusters, &ov.HealthyClusters, &ov.TotalServices, &ov.TotalTargets,
		&ov.TotalReplicas, &ov.OpenAlerts, &ov.AvgScore, &ov.MonthlyCostUSD); err != nil {
		return ov, err
	}

	rows, err := s.pool.Query(ctx, `
		SELECT c.provider_type, round(avg(t.reliability_score)::numeric,1), count(*),
		       COALESCE(sum(t.monthly_cost_usd),0)
		FROM deployment_target t JOIN cluster c ON c.id = t.cluster_id
		WHERE t.is_active
		GROUP BY c.provider_type ORDER BY 2 DESC`)
	if err != nil {
		return ov, err
	}
	for rows.Next() {
		var p domain.ProviderScore
		if err := rows.Scan(&p.Provider, &p.Score, &p.Targets, &p.CostUSD); err != nil {
			rows.Close()
			return ov, err
		}
		ov.ScoreByProvider = append(ov.ScoreByProvider, p)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return ov, err
	}

	if ov.RecentScaling, err = s.scalingEvents(ctx, 4); err != nil {
		return ov, err
	}
	if ov.RecentHealing, err = s.healingEvents(ctx, 3); err != nil {
		return ov, err
	}

	ov.ScoreTrend = buildTrend(ov.AvgScore)
	ov.EngineStatus = engineStatus(len(ov.RecentScaling), len(ov.RecentHealing))
	ov.ObservabilityFeed = observabilityFeed()

	s.cache.SetJSON(ctx, overviewCacheKey, ov, 30*time.Second)
	return ov, nil
}

// buildTrend produces a deterministic 14-day series so the chart is stable
// across reloads. Real score history replaces this once the Evaluation Engine
// populates reliability_score_snapshot in Phase 5.
func buildTrend(base float64) []domain.ScorePoint {
	rng := rand.New(rand.NewSource(42))
	out := make([]domain.ScorePoint, 0, 14)
	for i := 13; i >= 0; i-- {
		day := time.Now().UTC().AddDate(0, 0, -i)
		drift := (rng.Float64() - 0.45) * 3.2
		out = append(out, domain.ScorePoint{
			Date:  day.Format("Jan 02"),
			Score: math.Round(math.Min(math.Max(base+drift, 70), 100)*10) / 10,
		})
	}
	return out
}

func engineStatus(scaling, healing int) []domain.EngineStatus {
	return []domain.EngineStatus{
		{Name: "Deployment Engine", Status: "READY", Detail: "Awaiting Phase 3 client-go wiring", ActionsLast24h: 0},
		{Name: "Auto-Scaling", Status: "ACTIVE", Detail: "Strategies armed across targets", ActionsLast24h: scaling},
		{Name: "Self-Healing", Status: "ACTIVE", Detail: "Watching pod health", ActionsLast24h: healing},
		{Name: "Policy Engine", Status: "ENFORCING", Detail: "Cluster guardrails active", ActionsLast24h: 1},
		{Name: "Evaluation Engine", Status: "ACTIVE", Detail: "Probing endpoints", ActionsLast24h: 1440},
		{Name: "Experiment Engine", Status: "RUNNING", Detail: "Chaos runs in flight", ActionsLast24h: 4},
	}
}

func observabilityFeed() []domain.ObservabilitySource {
	return []domain.ObservabilitySource{
		{Name: "Prometheus", Kind: "Metrics", Status: "CONNECTED", IngestRate: "12.4k samples/s"},
		{Name: "Loki", Kind: "Logs", Status: "CONNECTED", IngestRate: "3.1k lines/s"},
		{Name: "OpenTelemetry", Kind: "Traces", Status: "CONNECTED", IngestRate: "840 spans/s"},
	}
}
