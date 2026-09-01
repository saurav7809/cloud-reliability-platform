-- AegisCloud initial schema.
-- Implements docs/phase-1-architecture/03-database.md. That document is the
-- design; this file is the source of truth. No ORM auto-migration anywhere.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ============================ tenancy & identity ============================

CREATE TABLE organization (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE app_user (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id        UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(20)  NOT NULL CHECK (role IN ('ADMIN','OPERATOR','VIEWER')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- =============================== registry ==================================

CREATE TABLE cluster (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id         UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    name           VARCHAR(120) NOT NULL,
    provider_type  VARCHAR(20)  NOT NULL
                   CHECK (provider_type IN ('AWS','GCP','AZURE','ON_PREM','KIND','OTHER')),
    distribution   VARCHAR(40),
    region         VARCHAR(60),
    kubeconfig_ref VARCHAR(255),
    status         VARCHAR(20) NOT NULL DEFAULT 'HEALTHY'
                   CHECK (status IN ('HEALTHY','DEGRADED','UNREACHABLE')),
    node_count     INT         NOT NULL DEFAULT 0,
    k8s_version    VARCHAR(30),
    is_local       BOOLEAN     NOT NULL DEFAULT false,
    is_active      BOOLEAN     NOT NULL DEFAULT true,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (org_id, name)
);

CREATE TABLE service (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    name        VARCHAR(120) NOT NULL,
    description TEXT,
    owner_team  VARCHAR(120),
    tags        JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (org_id, name)
);

CREATE TABLE policy (
    id                         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cluster_id                 UUID REFERENCES cluster(id) ON DELETE CASCADE,
    max_replicas               INT NOT NULL DEFAULT 10,
    max_concurrent_experiments INT NOT NULL DEFAULT 1,
    protected_namespaces       JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE deployment_target (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service_id        UUID NOT NULL REFERENCES service(id) ON DELETE CASCADE,
    cluster_id        UUID NOT NULL REFERENCES cluster(id) ON DELETE CASCADE,
    namespace         VARCHAR(120) NOT NULL DEFAULT 'default',
    label             VARCHAR(120),
    scaling_strategy  VARCHAR(20) NOT NULL DEFAULT 'NONE'
                      CHECK (scaling_strategy IN ('CPU','LATENCY','TREND','NONE')),
    deployment_status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                      CHECK (deployment_status IN ('PENDING','DEPLOYING','HEALTHY','DEGRADED','FAILED')),
    replicas          INT NOT NULL DEFAULT 0,
    desired_replicas  INT NOT NULL DEFAULT 0,
    -- Denormalised current readings. Authoritative history lives in metric_sample;
    -- these exist so the fleet table renders without aggregating on every request.
    reliability_score DOUBLE PRECISION NOT NULL DEFAULT 0,
    availability_pct  DOUBLE PRECISION NOT NULL DEFAULT 0,
    latency_p95_ms    DOUBLE PRECISION NOT NULL DEFAULT 0,
    error_rate_pct    DOUBLE PRECISION NOT NULL DEFAULT 0,
    monthly_cost_usd  DOUBLE PRECISION NOT NULL DEFAULT 0,
    is_active         BOOLEAN     NOT NULL DEFAULT true,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (service_id, cluster_id, namespace)
);

CREATE TABLE endpoint (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    target_id              UUID NOT NULL REFERENCES deployment_target(id) ON DELETE CASCADE,
    protocol               VARCHAR(10) NOT NULL CHECK (protocol IN ('HTTP','HTTPS','TCP','GRPC')),
    address                VARCHAR(500) NOT NULL,
    probe_interval_seconds INT NOT NULL DEFAULT 60,
    timeout_ms             INT NOT NULL DEFAULT 5000,
    expected_status_code   INT,
    is_active              BOOLEAN NOT NULL DEFAULT true
);

-- ============================== telemetry ==================================

CREATE TABLE metric_sample (
    id          BIGSERIAL PRIMARY KEY,
    target_id   UUID NOT NULL REFERENCES deployment_target(id) ON DELETE CASCADE,
    endpoint_id UUID REFERENCES endpoint(id) ON DELETE SET NULL,
    source      VARCHAR(20) NOT NULL CHECK (source IN ('PROBE','PROMETHEUS','OTEL','PUSHED')),
    metric_type VARCHAR(20) NOT NULL
                CHECK (metric_type IN ('AVAILABILITY','LATENCY_MS','ERROR_RATE','THROUGHPUT')),
    value       DOUBLE PRECISION NOT NULL,
    success     BOOLEAN,
    sampled_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_metric_sample_lookup ON metric_sample (target_id, metric_type, sampled_at DESC);
CREATE INDEX idx_metric_sample_retention ON metric_sample (sampled_at);

CREATE TABLE reliability_score_snapshot (
    id           BIGSERIAL PRIMARY KEY,
    target_id    UUID NOT NULL REFERENCES deployment_target(id) ON DELETE CASCADE,
    window_start TIMESTAMPTZ NOT NULL,
    window_end   TIMESTAMPTZ NOT NULL,
    score        DOUBLE PRECISION NOT NULL,
    computed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_score_trend ON reliability_score_snapshot (target_id, window_end DESC);

-- ============================== objectives =================================

CREATE TABLE slo (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    target_id       UUID NOT NULL REFERENCES deployment_target(id) ON DELETE CASCADE,
    sli_type        VARCHAR(20) NOT NULL
                    CHECK (sli_type IN ('AVAILABILITY','LATENCY_P95','LATENCY_P99','ERROR_RATE','THROUGHPUT')),
    objective_value DOUBLE PRECISION NOT NULL,
    window_days     INT NOT NULL DEFAULT 30,
    is_active       BOOLEAN NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE error_budget_snapshot (
    id                   BIGSERIAL PRIMARY KEY,
    slo_id               UUID NOT NULL REFERENCES slo(id) ON DELETE CASCADE,
    computed_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    current_value        DOUBLE PRECISION NOT NULL DEFAULT 0,
    budget_remaining_pct DOUBLE PRECISION NOT NULL,
    burn_rate            DOUBLE PRECISION NOT NULL
);
CREATE INDEX idx_budget_history ON error_budget_snapshot (slo_id, computed_at DESC);

-- ============================= control plane ===============================

CREATE TABLE scaling_event (
    id                BIGSERIAL PRIMARY KEY,
    target_id         UUID NOT NULL REFERENCES deployment_target(id) ON DELETE CASCADE,
    previous_replicas INT NOT NULL,
    new_replicas      INT NOT NULL,
    trigger_metric    VARCHAR(20) NOT NULL,
    trigger_value     DOUBLE PRECISION NOT NULL,
    strategy          VARCHAR(20) NOT NULL,
    decided_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_scaling_recent ON scaling_event (decided_at DESC);

CREATE TABLE healing_event (
    id           BIGSERIAL PRIMARY KEY,
    target_id    UUID NOT NULL REFERENCES deployment_target(id) ON DELETE CASCADE,
    pod_name     VARCHAR(255) NOT NULL,
    reason       VARCHAR(50)  NOT NULL,
    action_taken VARCHAR(20)  NOT NULL CHECK (action_taken IN ('RESTARTED','RESCHEDULED','ESCALATED')),
    detected_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at  TIMESTAMPTZ
);
CREATE INDEX idx_healing_recent ON healing_event (detected_at DESC);

-- ============================== evaluation =================================

CREATE TABLE evaluation_run (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service_id   UUID NOT NULL REFERENCES service(id) ON DELETE CASCADE,
    target_id    UUID REFERENCES deployment_target(id) ON DELETE SET NULL,
    run_type     VARCHAR(20) NOT NULL CHECK (run_type IN ('SCHEDULED_PROBE','CHAOS','MANUAL')),
    triggered_by UUID REFERENCES app_user(id) ON DELETE SET NULL,
    fault_spec   JSONB,
    status       VARCHAR(20) NOT NULL DEFAULT 'RUNNING'
                 CHECK (status IN ('RUNNING','COMPLETED','FAILED','ABORTED','REJECTED_BY_POLICY')),
    score_before DOUBLE PRECISION,
    score_during DOUBLE PRECISION,
    score_after  DOUBLE PRECISION,
    started_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at     TIMESTAMPTZ
);
CREATE INDEX idx_eval_recent ON evaluation_run (started_at DESC);

CREATE TABLE evaluation_run_metric (
    id               BIGSERIAL PRIMARY KEY,
    evaluation_run_id UUID NOT NULL REFERENCES evaluation_run(id) ON DELETE CASCADE,
    metric_sample_id  BIGINT NOT NULL REFERENCES metric_sample(id) ON DELETE CASCADE,
    phase             VARCHAR(10) NOT NULL CHECK (phase IN ('BEFORE','DURING','AFTER'))
);

-- =============================== alerting ==================================

CREATE TABLE incident (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id               UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    title                VARCHAR(255) NOT NULL,
    status               VARCHAR(20)  NOT NULL DEFAULT 'OPEN'
                         CHECK (status IN ('OPEN','DIAGNOSING','MITIGATING','RESOLVED')),
    root_cause_target_id UUID REFERENCES deployment_target(id) ON DELETE SET NULL,
    confidence           DOUBLE PRECISION,
    blast_radius_count   INT NOT NULL DEFAULT 0,
    started_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at          TIMESTAMPTZ
);

CREATE TABLE alert (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    target_id        UUID NOT NULL REFERENCES deployment_target(id) ON DELETE CASCADE,
    slo_id           UUID REFERENCES slo(id) ON DELETE SET NULL,
    incident_id      UUID REFERENCES incident(id) ON DELETE SET NULL,
    severity         VARCHAR(10) NOT NULL CHECK (severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    status           VARCHAR(20) NOT NULL DEFAULT 'OPEN'
                     CHECK (status IN ('OPEN','ACKNOWLEDGED','RESOLVED')),
    message          TEXT NOT NULL,
    opened_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    acknowledged_at  TIMESTAMPTZ,
    acknowledged_by  UUID REFERENCES app_user(id) ON DELETE SET NULL,
    resolved_at      TIMESTAMPTZ
);
CREATE INDEX idx_alert_feed ON alert (opened_at DESC);
CREATE INDEX idx_alert_status ON alert (status);

-- ============================= intelligence ================================

-- The only self-referential relation: both ends point at service. This is what
-- makes the dependency graph a graph.
CREATE TABLE service_dependency (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    caller_service_id UUID NOT NULL REFERENCES service(id) ON DELETE CASCADE,
    callee_service_id UUID NOT NULL REFERENCES service(id) ON DELETE CASCADE,
    discovery_source  VARCHAR(20) NOT NULL DEFAULT 'TRACE'
                      CHECK (discovery_source IN ('TRACE','MANUAL')),
    call_rate_per_min DOUBLE PRECISION NOT NULL DEFAULT 0,
    error_rate_pct    DOUBLE PRECISION NOT NULL DEFAULT 0,
    latency_p95_ms    DOUBLE PRECISION NOT NULL DEFAULT 0,
    last_seen_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (caller_service_id, callee_service_id),
    CHECK (caller_service_id <> callee_service_id)
);
-- Hot path for "who depends on this?" blast-radius queries.
CREATE INDEX idx_dependency_callee ON service_dependency (callee_service_id);

CREATE TABLE rca_verdict (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id         UUID NOT NULL REFERENCES incident(id) ON DELETE CASCADE,
    candidate_target_id UUID NOT NULL REFERENCES deployment_target(id) ON DELETE CASCADE,
    rank                INT NOT NULL,
    confidence          DOUBLE PRECISION NOT NULL,
    reasoning           TEXT NOT NULL,
    evidence            JSONB NOT NULL DEFAULT '{}'::jsonb,
    signal_scores       JSONB NOT NULL DEFAULT '{}'::jsonb,
    human_verdict       VARCHAR(20) CHECK (human_verdict IN ('CORRECT','INCORRECT')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (incident_id, rank)
);

CREATE TABLE recommendation (
    id                           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    target_id                    UUID NOT NULL REFERENCES deployment_target(id) ON DELETE CASCADE,
    kind                         VARCHAR(30) NOT NULL,
    title                        VARCHAR(255) NOT NULL,
    rationale                    TEXT,
    evidence                     JSONB NOT NULL DEFAULT '{}'::jsonb,
    estimated_monthly_saving_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
    reliability_impact           VARCHAR(20) NOT NULL DEFAULT 'NONE'
                                 CHECK (reliability_impact IN ('NONE','LOW','MEDIUM','HIGH')),
    status                       VARCHAR(20) NOT NULL DEFAULT 'OPEN'
                                 CHECK (status IN ('OPEN','APPLIED','DISMISSED','REVERTED')),
    applied_by                   UUID REFERENCES app_user(id) ON DELETE SET NULL,
    applied_at                   TIMESTAMPTZ,
    outcome                      TEXT,
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =============================== autonomy ==================================

CREATE TABLE autonomy_setting (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cluster_id  UUID REFERENCES cluster(id) ON DELETE CASCADE,
    action_type VARCHAR(30) NOT NULL,
    -- Defaults to SUGGEST per FR-36: the platform does not act unattended
    -- until explicitly permitted.
    level       VARCHAR(10) NOT NULL DEFAULT 'SUGGEST'
                CHECK (level IN ('OBSERVE','SUGGEST','ACT')),
    updated_by  UUID REFERENCES app_user(id) ON DELETE SET NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (cluster_id, action_type)
);

CREATE TABLE autonomous_action (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id  UUID REFERENCES incident(id) ON DELETE SET NULL,
    target_id    UUID NOT NULL REFERENCES deployment_target(id) ON DELETE CASCADE,
    action_type  VARCHAR(30) NOT NULL,
    observed     JSONB NOT NULL DEFAULT '{}'::jsonb,
    concluded    TEXT,
    executed     JSONB NOT NULL DEFAULT '{}'::jsonb,
    policy_check VARCHAR(20) NOT NULL CHECK (policy_check IN ('PASSED','REJECTED')),
    outcome      VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                 CHECK (outcome IN ('PENDING','IMPROVED','NO_CHANGE','WORSENED','ROLLED_BACK')),
    score_before DOUBLE PRECISION,
    score_after  DOUBLE PRECISION,
    executed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    verified_at  TIMESTAMPTZ
);

-- ================================ audit ====================================

CREATE TABLE audit_log_entry (
    id           BIGSERIAL PRIMARY KEY,
    org_id       UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    actor_id     UUID REFERENCES app_user(id) ON DELETE SET NULL,
    actor_kind   VARCHAR(20) NOT NULL DEFAULT 'USER'
                 CHECK (actor_kind IN ('USER','ENGINE')),
    action       VARCHAR(50) NOT NULL,
    entity_type  VARCHAR(50) NOT NULL,
    entity_id    VARCHAR(64),
    before_state JSONB,
    after_state  JSONB,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_recent ON audit_log_entry (created_at DESC);
