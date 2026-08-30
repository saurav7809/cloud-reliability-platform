// Package store is AegisCloud's data layer.
//
// PHASE 2 NOTE: this is an in-memory store holding a seeded demo fleet so the
// dashboard has a realistic platform to render. It is NOT reading from live
// clusters — that arrives in Phase 3 with the client-go Deployment Engine, at
// which point this package is backed by PostgreSQL using the schema in
// docs/phase-1-architecture/03-database.md. The exported method set is written to
// survive that swap unchanged.
package store

import (
	"fmt"
	"math"
	"math/rand"
	"sort"
	"sync"
	"time"

	"github.com/aegiscloud/backend/internal/domain"
)

type Store struct {
	mu sync.RWMutex

	clusters    []domain.Cluster
	services    []domain.Service
	targets     []domain.DeploymentTarget
	slos        []domain.Slo
	scaling     []domain.ScalingEvent
	healing     []domain.HealingEvent
	alerts      []domain.Alert
	experiments []domain.ExperimentRun
	policies    []domain.Policy
}

func New() *Store {
	s := &Store{}
	s.seed()
	return s
}

func (s *Store) seed() {
	now := time.Now().UTC()

	s.clusters = []domain.Cluster{
		{ID: "cl-kind-local", Name: "aegiscloud-local", Provider: domain.ProviderKind,
			Distribution: "kind", Region: "local", Status: domain.ClusterHealthy,
			NodeCount: 1, K8sVersion: "v1.37.0", IsLocal: true},
		{ID: "cl-eks-use1", Name: "prod-eks-use1", Provider: domain.ProviderAWS,
			Distribution: "EKS", Region: "us-east-1", Status: domain.ClusterHealthy,
			NodeCount: 6, K8sVersion: "v1.30.2"},
		{ID: "cl-gke-usc1", Name: "prod-gke-usc1", Provider: domain.ProviderGCP,
			Distribution: "GKE", Region: "us-central1", Status: domain.ClusterHealthy,
			NodeCount: 5, K8sVersion: "v1.30.1"},
		{ID: "cl-aks-weu", Name: "prod-aks-weu", Provider: domain.ProviderAzure,
			Distribution: "AKS", Region: "westeurope", Status: domain.ClusterDegraded,
			NodeCount: 4, K8sVersion: "v1.29.7"},
	}

	s.services = []domain.Service{
		{ID: "svc-checkout", Name: "checkout-service", OwnerTeam: "payments",
			Description: "Order checkout and payment capture",
			Tags: map[string]string{"env": "prod", "tier": "critical"}},
		{ID: "svc-catalog", Name: "catalog-service", OwnerTeam: "commerce",
			Description: "Product catalog and search",
			Tags: map[string]string{"env": "prod", "tier": "high"}},
		{ID: "svc-auth", Name: "auth-service", OwnerTeam: "platform",
			Description: "Identity, sessions and token issuance",
			Tags: map[string]string{"env": "prod", "tier": "critical"}},
	}

	type targetSeed struct {
		svcID, svcName, clID, clName, ns string
		provider                         domain.ProviderType
		region                           string
		strategy                         domain.ScalingStrategy
		status                           domain.DeploymentStatus
		replicas, desired                int
		score, avail, p95, errRate, cost float64
	}

	seeds := []targetSeed{
		{"svc-checkout", "checkout-service", "cl-eks-use1", "prod-eks-use1", "checkout",
			domain.ProviderAWS, "us-east-1", domain.StrategyCPU, domain.DeploymentHealthy,
			6, 6, 96.4, 99.94, 182.4, 0.06, 1284.50},
		{"svc-checkout", "checkout-service", "cl-gke-usc1", "prod-gke-usc1", "checkout",
			domain.ProviderGCP, "us-central1", domain.StrategyLatency, domain.DeploymentHealthy,
			5, 5, 91.2, 99.81, 241.7, 0.19, 1102.30},
		{"svc-catalog", "catalog-service", "cl-eks-use1", "prod-eks-use1", "catalog",
			domain.ProviderAWS, "us-east-1", domain.StrategyTrend, domain.DeploymentHealthy,
			4, 4, 98.1, 99.98, 94.2, 0.02, 742.10},
		{"svc-catalog", "catalog-service", "cl-aks-weu", "prod-aks-weu", "catalog",
			domain.ProviderAzure, "westeurope", domain.StrategyCPU, domain.DeploymentDegraded,
			3, 5, 78.6, 99.12, 512.9, 0.88, 689.40},
		{"svc-auth", "auth-service", "cl-eks-use1", "prod-eks-use1", "auth",
			domain.ProviderAWS, "us-east-1", domain.StrategyCPU, domain.DeploymentHealthy,
			8, 8, 99.2, 99.99, 41.8, 0.01, 1620.00},
		{"svc-auth", "auth-service", "cl-kind-local", "aegiscloud-local", "aegiscloud",
			domain.ProviderKind, "local", domain.StrategyNone, domain.DeploymentHealthy,
			1, 1, 94.0, 99.90, 12.4, 0.00, 0.00},
	}

	for i, t := range seeds {
		s.targets = append(s.targets, domain.DeploymentTarget{
			ID: fmt.Sprintf("tgt-%02d", i+1), ServiceID: t.svcID, ServiceName: t.svcName,
			ClusterID: t.clID, ClusterName: t.clName, Provider: t.provider, Region: t.region,
			Namespace: t.ns, ScalingStrategy: t.strategy, Status: t.status,
			Replicas: t.replicas, DesiredReplicas: t.desired, ReliabilityScore: t.score,
			AvailabilityPct: t.avail, LatencyP95Ms: t.p95, ErrorRatePct: t.errRate,
			MonthlyCostUSD: t.cost,
		})
	}

	s.slos = []domain.Slo{
		{ID: "slo-1", TargetID: "tgt-01", TargetLabel: "checkout-service @ prod-eks-use1",
			SliType: domain.SliAvailability, ObjectiveValue: 99.9, WindowDays: 30,
			CurrentValue: 99.94, BudgetRemainingPct: 62.0, BurnRate: 0.7},
		{ID: "slo-2", TargetID: "tgt-01", TargetLabel: "checkout-service @ prod-eks-use1",
			SliType: domain.SliLatencyP95, ObjectiveValue: 250, WindowDays: 30,
			CurrentValue: 182.4, BudgetRemainingPct: 81.0, BurnRate: 0.4},
		{ID: "slo-3", TargetID: "tgt-02", TargetLabel: "checkout-service @ prod-gke-usc1",
			SliType: domain.SliAvailability, ObjectiveValue: 99.9, WindowDays: 30,
			CurrentValue: 99.81, BudgetRemainingPct: 18.0, BurnRate: 2.4},
		{ID: "slo-4", TargetID: "tgt-04", TargetLabel: "catalog-service @ prod-aks-weu",
			SliType: domain.SliAvailability, ObjectiveValue: 99.9, WindowDays: 30,
			CurrentValue: 99.12, BudgetRemainingPct: 0.0, BurnRate: 8.9},
		{ID: "slo-5", TargetID: "tgt-05", TargetLabel: "auth-service @ prod-eks-use1",
			SliType: domain.SliAvailability, ObjectiveValue: 99.95, WindowDays: 30,
			CurrentValue: 99.99, BudgetRemainingPct: 91.0, BurnRate: 0.2},
	}

	s.scaling = []domain.ScalingEvent{
		{ID: "se-1", TargetID: "tgt-05", TargetLabel: "auth-service @ prod-eks-use1",
			PreviousReplicas: 6, NewReplicas: 8, TriggerMetric: "CPU", TriggerValue: 81.4,
			Strategy: domain.StrategyCPU, DecidedAt: now.Add(-4 * time.Minute)},
		{ID: "se-2", TargetID: "tgt-02", TargetLabel: "checkout-service @ prod-gke-usc1",
			PreviousReplicas: 4, NewReplicas: 5, TriggerMetric: "LATENCY_P95", TriggerValue: 268.2,
			Strategy: domain.StrategyLatency, DecidedAt: now.Add(-17 * time.Minute)},
		{ID: "se-3", TargetID: "tgt-03", TargetLabel: "catalog-service @ prod-eks-use1",
			PreviousReplicas: 5, NewReplicas: 4, TriggerMetric: "TREND", TriggerValue: -12.6,
			Strategy: domain.StrategyTrend, DecidedAt: now.Add(-41 * time.Minute)},
		{ID: "se-4", TargetID: "tgt-01", TargetLabel: "checkout-service @ prod-eks-use1",
			PreviousReplicas: 5, NewReplicas: 6, TriggerMetric: "CPU", TriggerValue: 76.9,
			Strategy: domain.StrategyCPU, DecidedAt: now.Add(-68 * time.Minute)},
	}

	resolved := now.Add(-22 * time.Minute)
	s.healing = []domain.HealingEvent{
		{ID: "he-1", TargetID: "tgt-04", TargetLabel: "catalog-service @ prod-aks-weu",
			PodName: "catalog-7d9f4b-x2mq", Reason: "CRASH_LOOP", ActionTaken: "RESTARTED",
			DetectedAt: now.Add(-9 * time.Minute)},
		{ID: "he-2", TargetID: "tgt-04", TargetLabel: "catalog-service @ prod-aks-weu",
			PodName: "catalog-7d9f4b-k8tp", Reason: "OOM_KILLED", ActionTaken: "RESCHEDULED",
			DetectedAt: now.Add(-25 * time.Minute), ResolvedAt: &resolved},
		{ID: "he-3", TargetID: "tgt-02", TargetLabel: "checkout-service @ prod-gke-usc1",
			PodName: "checkout-5c8a1e-vv4d", Reason: "NOT_READY", ActionTaken: "RESTARTED",
			DetectedAt: now.Add(-53 * time.Minute), ResolvedAt: &resolved},
	}

	s.alerts = []domain.Alert{
		{ID: "al-1", TargetID: "tgt-04", TargetLabel: "catalog-service @ prod-aks-weu",
			Severity: domain.SeverityCritical, Status: domain.AlertOpen,
			Message: "Error budget exhausted — burn rate 8.9x sustainable",
			OpenedAt: now.Add(-9 * time.Minute)},
		{ID: "al-2", TargetID: "tgt-02", TargetLabel: "checkout-service @ prod-gke-usc1",
			Severity: domain.SeverityHigh, Status: domain.AlertOpen,
			Message: "Availability SLO burn rate 2.4x — 18% budget remaining",
			OpenedAt: now.Add(-31 * time.Minute)},
		{ID: "al-3", TargetID: "tgt-04", TargetLabel: "catalog-service @ prod-aks-weu",
			Severity: domain.SeverityMedium, Status: domain.AlertAcknowledged,
			Message: "p95 latency 512.9ms exceeds 250ms objective",
			OpenedAt: now.Add(-2 * time.Hour)},
		{ID: "al-4", TargetID: "tgt-01", TargetLabel: "checkout-service @ prod-eks-use1",
			Severity: domain.SeverityLow, Status: domain.AlertResolved,
			Message: "Transient replica shortfall during rollout",
			OpenedAt: now.Add(-6 * time.Hour)},
	}

	end1 := now.Add(-3 * time.Hour)
	end2 := now.Add(-26 * time.Hour)
	s.experiments = []domain.ExperimentRun{
		{ID: "exp-1", ServiceName: "checkout-service", TargetLabel: "prod-eks-use1",
			RunType: domain.RunChaos, FaultType: "LATENCY_INJECTION", Status: domain.RunCompleted,
			ScoreBefore: 96.8, ScoreDuring: 71.2, ScoreAfter: 96.4,
			StartedAt: now.Add(-3*time.Hour - 12*time.Minute), EndedAt: &end1},
		{ID: "exp-2", ServiceName: "auth-service", TargetLabel: "prod-eks-use1",
			RunType: domain.RunChaos, FaultType: "POD_KILL", Status: domain.RunCompleted,
			ScoreBefore: 99.4, ScoreDuring: 88.1, ScoreAfter: 99.2,
			StartedAt: now.Add(-26*time.Hour - 8*time.Minute), EndedAt: &end2},
		{ID: "exp-3", ServiceName: "catalog-service", TargetLabel: "prod-aks-weu",
			RunType: domain.RunChaos, FaultType: "RESOURCE_STARVATION", Status: domain.RunRejected,
			StartedAt: now.Add(-45 * time.Minute)},
		{ID: "exp-4", ServiceName: "catalog-service", TargetLabel: "prod-eks-use1",
			RunType: domain.RunChaos, FaultType: "NETWORK_PARTITION", Status: domain.RunRunning,
			ScoreBefore: 98.1, ScoreDuring: 82.4, StartedAt: now.Add(-6 * time.Minute)},
	}

	s.policies = []domain.Policy{
		{ID: "pol-1", ClusterID: "cl-eks-use1", ClusterName: "prod-eks-use1",
			MaxReplicas: 20, MaxConcurrentExperiments: 2,
			ProtectedNamespaces: []string{"kube-system", "istio-system"}},
		{ID: "pol-2", ClusterID: "cl-gke-usc1", ClusterName: "prod-gke-usc1",
			MaxReplicas: 15, MaxConcurrentExperiments: 1,
			ProtectedNamespaces: []string{"kube-system"}},
		{ID: "pol-3", ClusterID: "cl-aks-weu", ClusterName: "prod-aks-weu",
			MaxReplicas: 12, MaxConcurrentExperiments: 0,
			ProtectedNamespaces: []string{"kube-system", "gatekeeper-system"}},
		{ID: "pol-4", ClusterID: "cl-kind-local", ClusterName: "aegiscloud-local",
			MaxReplicas: 3, MaxConcurrentExperiments: 1,
			ProtectedNamespaces: []string{"kube-system", "local-path-storage"}},
	}
}

func (s *Store) Clusters() []domain.Cluster {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return append([]domain.Cluster(nil), s.clusters...)
}

func (s *Store) Services() []domain.Service {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return append([]domain.Service(nil), s.services...)
}

func (s *Store) Targets() []domain.DeploymentTarget {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return append([]domain.DeploymentTarget(nil), s.targets...)
}

func (s *Store) Slos() []domain.Slo {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return append([]domain.Slo(nil), s.slos...)
}

func (s *Store) ScalingEvents() []domain.ScalingEvent {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := append([]domain.ScalingEvent(nil), s.scaling...)
	sort.Slice(out, func(i, j int) bool { return out[i].DecidedAt.After(out[j].DecidedAt) })
	return out
}

func (s *Store) HealingEvents() []domain.HealingEvent {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := append([]domain.HealingEvent(nil), s.healing...)
	sort.Slice(out, func(i, j int) bool { return out[i].DetectedAt.After(out[j].DetectedAt) })
	return out
}

func (s *Store) Alerts() []domain.Alert {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := append([]domain.Alert(nil), s.alerts...)
	sort.Slice(out, func(i, j int) bool { return out[i].OpenedAt.After(out[j].OpenedAt) })
	return out
}

func (s *Store) Experiments() []domain.ExperimentRun {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := append([]domain.ExperimentRun(nil), s.experiments...)
	sort.Slice(out, func(i, j int) bool { return out[i].StartedAt.After(out[j].StartedAt) })
	return out
}

func (s *Store) Policies() []domain.Policy {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return append([]domain.Policy(nil), s.policies...)
}

// SetAlertStatus transitions an alert through its OPEN -> ACKNOWLEDGED -> RESOLVED
// lifecycle. Returns false if no alert with that id exists.
func (s *Store) SetAlertStatus(id string, status domain.AlertStatus) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	for i := range s.alerts {
		if s.alerts[i].ID == id {
			s.alerts[i].Status = status
			return true
		}
	}
	return false
}

func (s *Store) Overview() domain.Overview {
	s.mu.RLock()
	defer s.mu.RUnlock()

	ov := domain.Overview{
		TotalClusters: len(s.clusters),
		TotalServices: len(s.services),
		TotalTargets:  len(s.targets),
	}

	for _, c := range s.clusters {
		if c.Status == domain.ClusterHealthy {
			ov.HealthyClusters++
		}
	}

	type agg struct {
		sum  float64
		n    int
		cost float64
	}
	byProvider := map[domain.ProviderType]*agg{}
	var scoreSum float64

	for _, t := range s.targets {
		ov.TotalReplicas += t.Replicas
		ov.MonthlyCostUSD += t.MonthlyCostUSD
		scoreSum += t.ReliabilityScore
		a, ok := byProvider[t.Provider]
		if !ok {
			a = &agg{}
			byProvider[t.Provider] = a
		}
		a.sum += t.ReliabilityScore
		a.n++
		a.cost += t.MonthlyCostUSD
	}

	if len(s.targets) > 0 {
		ov.AvgScore = round1(scoreSum / float64(len(s.targets)))
	}

	for p, a := range byProvider {
		ov.ScoreByProvider = append(ov.ScoreByProvider, domain.ProviderScore{
			Provider: p, Score: round1(a.sum / float64(a.n)), Targets: a.n, CostUSD: a.cost,
		})
	}
	sort.Slice(ov.ScoreByProvider, func(i, j int) bool {
		return ov.ScoreByProvider[i].Score > ov.ScoreByProvider[j].Score
	})

	for _, a := range s.alerts {
		if a.Status == domain.AlertOpen {
			ov.OpenAlerts++
		}
	}

	// Deterministic 14-day trend so the chart is stable across reloads.
	rng := rand.New(rand.NewSource(42))
	base := ov.AvgScore
	for i := 13; i >= 0; i-- {
		day := time.Now().UTC().AddDate(0, 0, -i)
		drift := (rng.Float64() - 0.45) * 3.2
		ov.ScoreTrend = append(ov.ScoreTrend, domain.ScorePoint{
			Date:  day.Format("Jan 02"),
			Score: round1(clamp(base+drift, 70, 100)),
		})
	}

	ov.RecentScaling = topScaling(s.scaling, 4)
	ov.RecentHealing = topHealing(s.healing, 3)

	ov.EngineStatus = []domain.EngineStatus{
		{Name: "Deployment Engine", Status: "READY", Detail: "Awaiting Phase 3 client-go wiring", ActionsLast24h: 0},
		{Name: "Auto-Scaling", Status: "ACTIVE", Detail: "4 strategies armed across 6 targets", ActionsLast24h: len(s.scaling)},
		{Name: "Self-Healing", Status: "ACTIVE", Detail: "Watching 27 pods", ActionsLast24h: len(s.healing)},
		{Name: "Policy Engine", Status: "ENFORCING", Detail: "4 cluster policies, 1 experiment rejected", ActionsLast24h: 1},
		{Name: "Evaluation Engine", Status: "ACTIVE", Detail: "Probing 6 endpoints", ActionsLast24h: 1440},
		{Name: "Experiment Engine", Status: "RUNNING", Detail: "1 chaos run in flight", ActionsLast24h: 4},
	}

	ov.ObservabilityFeed = []domain.ObservabilitySource{
		{Name: "Prometheus", Kind: "Metrics", Status: "CONNECTED", IngestRate: "12.4k samples/s"},
		{Name: "Loki", Kind: "Logs", Status: "CONNECTED", IngestRate: "3.1k lines/s"},
		{Name: "OpenTelemetry", Kind: "Traces", Status: "CONNECTED", IngestRate: "840 spans/s"},
	}

	return ov
}

func topScaling(in []domain.ScalingEvent, n int) []domain.ScalingEvent {
	out := append([]domain.ScalingEvent(nil), in...)
	sort.Slice(out, func(i, j int) bool { return out[i].DecidedAt.After(out[j].DecidedAt) })
	if len(out) > n {
		out = out[:n]
	}
	return out
}

func topHealing(in []domain.HealingEvent, n int) []domain.HealingEvent {
	out := append([]domain.HealingEvent(nil), in...)
	sort.Slice(out, func(i, j int) bool { return out[i].DetectedAt.After(out[j].DetectedAt) })
	if len(out) > n {
		out = out[:n]
	}
	return out
}

func round1(v float64) float64 { return math.Round(v*10) / 10 }

func clamp(v, lo, hi float64) float64 {
	return math.Min(math.Max(v, lo), hi)
}
