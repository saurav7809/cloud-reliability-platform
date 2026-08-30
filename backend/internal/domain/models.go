// Package domain holds AegisCloud's provider-neutral core types.
//
// Nothing in this package imports a cloud SDK — an AWS EKS cluster, an Azure AKS
// cluster, a GCP GKE cluster and a local kind cluster are all just a Cluster with a
// different ProviderType. That is the structural guarantee behind "cloud-agnostic";
// see docs/phase-1-architecture/02-architecture.md.
package domain

import "time"

type ProviderType string

const (
	ProviderAWS    ProviderType = "AWS"
	ProviderGCP    ProviderType = "GCP"
	ProviderAzure  ProviderType = "AZURE"
	ProviderKind   ProviderType = "KIND"
	ProviderOnPrem ProviderType = "ON_PREM"
)

type ClusterStatus string

const (
	ClusterHealthy     ClusterStatus = "HEALTHY"
	ClusterDegraded    ClusterStatus = "DEGRADED"
	ClusterUnreachable ClusterStatus = "UNREACHABLE"
)

// Cluster is a registered Kubernetes cluster. The Distribution field (EKS/AKS/GKE/kind)
// is descriptive metadata only — it never changes control flow.
type Cluster struct {
	ID           string        `json:"id"`
	Name         string        `json:"name"`
	Provider     ProviderType  `json:"provider"`
	Distribution string        `json:"distribution"`
	Region       string        `json:"region"`
	Status       ClusterStatus `json:"status"`
	NodeCount    int           `json:"nodeCount"`
	K8sVersion   string        `json:"k8sVersion"`
	IsLocal      bool          `json:"isLocal"`
}

type Service struct {
	ID          string            `json:"id"`
	Name        string            `json:"name"`
	OwnerTeam   string            `json:"ownerTeam"`
	Description string            `json:"description"`
	Tags        map[string]string `json:"tags"`
}

type DeploymentStatus string

const (
	DeploymentHealthy   DeploymentStatus = "HEALTHY"
	DeploymentDegraded  DeploymentStatus = "DEGRADED"
	DeploymentDeploying DeploymentStatus = "DEPLOYING"
	DeploymentFailed    DeploymentStatus = "FAILED"
)

type ScalingStrategy string

const (
	StrategyCPU     ScalingStrategy = "CPU"
	StrategyLatency ScalingStrategy = "LATENCY"
	StrategyTrend   ScalingStrategy = "TREND"
	StrategyNone    ScalingStrategy = "NONE"
)

// DeploymentTarget is one service running on one cluster — the unit AegisCloud
// scales, heals, evaluates and scores.
type DeploymentTarget struct {
	ID               string           `json:"id"`
	ServiceID        string           `json:"serviceId"`
	ServiceName      string           `json:"serviceName"`
	ClusterID        string           `json:"clusterId"`
	ClusterName      string           `json:"clusterName"`
	Provider         ProviderType     `json:"provider"`
	Region           string           `json:"region"`
	Namespace        string           `json:"namespace"`
	ScalingStrategy  ScalingStrategy  `json:"scalingStrategy"`
	Status           DeploymentStatus `json:"status"`
	Replicas         int              `json:"replicas"`
	DesiredReplicas  int              `json:"desiredReplicas"`
	ReliabilityScore float64          `json:"reliabilityScore"`
	AvailabilityPct  float64          `json:"availabilityPct"`
	LatencyP95Ms     float64          `json:"latencyP95Ms"`
	ErrorRatePct     float64          `json:"errorRatePct"`
	MonthlyCostUSD   float64          `json:"monthlyCostUsd"`
}

type SliType string

const (
	SliAvailability SliType = "AVAILABILITY"
	SliLatencyP95   SliType = "LATENCY_P95"
	SliErrorRate    SliType = "ERROR_RATE"
)

type Slo struct {
	ID                 string  `json:"id"`
	TargetID           string  `json:"targetId"`
	TargetLabel        string  `json:"targetLabel"`
	SliType            SliType `json:"sliType"`
	ObjectiveValue     float64 `json:"objectiveValue"`
	WindowDays         int     `json:"windowDays"`
	CurrentValue       float64 `json:"currentValue"`
	BudgetRemainingPct float64 `json:"budgetRemainingPct"`
	BurnRate           float64 `json:"burnRate"`
}

type ScalingEvent struct {
	ID               string          `json:"id"`
	TargetID         string          `json:"targetId"`
	TargetLabel      string          `json:"targetLabel"`
	PreviousReplicas int             `json:"previousReplicas"`
	NewReplicas      int             `json:"newReplicas"`
	TriggerMetric    string          `json:"triggerMetric"`
	TriggerValue     float64         `json:"triggerValue"`
	Strategy         ScalingStrategy `json:"strategy"`
	DecidedAt        time.Time       `json:"decidedAt"`
}

type HealingEvent struct {
	ID          string     `json:"id"`
	TargetID    string     `json:"targetId"`
	TargetLabel string     `json:"targetLabel"`
	PodName     string     `json:"podName"`
	Reason      string     `json:"reason"`
	ActionTaken string     `json:"actionTaken"`
	DetectedAt  time.Time  `json:"detectedAt"`
	ResolvedAt  *time.Time `json:"resolvedAt"`
}

type AlertSeverity string

const (
	SeverityLow      AlertSeverity = "LOW"
	SeverityMedium   AlertSeverity = "MEDIUM"
	SeverityHigh     AlertSeverity = "HIGH"
	SeverityCritical AlertSeverity = "CRITICAL"
)

type AlertStatus string

const (
	AlertOpen         AlertStatus = "OPEN"
	AlertAcknowledged AlertStatus = "ACKNOWLEDGED"
	AlertResolved     AlertStatus = "RESOLVED"
)

type Alert struct {
	ID          string        `json:"id"`
	TargetID    string        `json:"targetId"`
	TargetLabel string        `json:"targetLabel"`
	Severity    AlertSeverity `json:"severity"`
	Status      AlertStatus   `json:"status"`
	Message     string        `json:"message"`
	OpenedAt    time.Time     `json:"openedAt"`
}

type RunType string

const (
	RunScheduledProbe RunType = "SCHEDULED_PROBE"
	RunChaos          RunType = "CHAOS"
	RunManual         RunType = "MANUAL"
)

type RunStatus string

const (
	RunRunning   RunStatus = "RUNNING"
	RunCompleted RunStatus = "COMPLETED"
	RunFailed    RunStatus = "FAILED"
	RunAborted   RunStatus = "ABORTED"
	RunRejected  RunStatus = "REJECTED_BY_POLICY"
)

type ExperimentRun struct {
	ID          string     `json:"id"`
	ServiceName string     `json:"serviceName"`
	TargetLabel string     `json:"targetLabel"`
	RunType     RunType    `json:"runType"`
	FaultType   string     `json:"faultType"`
	Status      RunStatus  `json:"status"`
	ScoreBefore float64    `json:"scoreBefore"`
	ScoreDuring float64    `json:"scoreDuring"`
	ScoreAfter  float64    `json:"scoreAfter"`
	StartedAt   time.Time  `json:"startedAt"`
	EndedAt     *time.Time `json:"endedAt"`
}

// Policy holds the guardrails the Policy Engine enforces before Auto-Scaling,
// Self-Healing or the Experiment Engine act.
type Policy struct {
	ID                       string   `json:"id"`
	ClusterID                string   `json:"clusterId"`
	ClusterName              string   `json:"clusterName"`
	MaxReplicas              int      `json:"maxReplicas"`
	MaxConcurrentExperiments int      `json:"maxConcurrentExperiments"`
	ProtectedNamespaces      []string `json:"protectedNamespaces"`
}

// Overview is the dashboard summary rollup.
type Overview struct {
	TotalClusters     int                  `json:"totalClusters"`
	HealthyClusters   int                  `json:"healthyClusters"`
	TotalServices     int                  `json:"totalServices"`
	TotalTargets      int                  `json:"totalTargets"`
	TotalReplicas     int                  `json:"totalReplicas"`
	OpenAlerts        int                  `json:"openAlerts"`
	AvgScore          float64              `json:"avgScore"`
	MonthlyCostUSD    float64              `json:"monthlyCostUsd"`
	ScoreByProvider   []ProviderScore      `json:"scoreByProvider"`
	ScoreTrend        []ScorePoint         `json:"scoreTrend"`
	RecentScaling     []ScalingEvent       `json:"recentScaling"`
	RecentHealing     []HealingEvent       `json:"recentHealing"`
	EngineStatus      []EngineStatus       `json:"engineStatus"`
	ObservabilityFeed []ObservabilitySource `json:"observabilityFeed"`
}

type ProviderScore struct {
	Provider  ProviderType `json:"provider"`
	Score     float64      `json:"score"`
	Targets   int          `json:"targets"`
	CostUSD   float64      `json:"costUsd"`
}

type ScorePoint struct {
	Date  string  `json:"date"`
	Score float64 `json:"score"`
}

type EngineStatus struct {
	Name       string `json:"name"`
	Status     string `json:"status"`
	Detail     string `json:"detail"`
	ActionsLast24h int `json:"actionsLast24h"`
}

type ObservabilitySource struct {
	Name       string `json:"name"`
	Kind       string `json:"kind"`
	Status     string `json:"status"`
	IngestRate string `json:"ingestRate"`
}
