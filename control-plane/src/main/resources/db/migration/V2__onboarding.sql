-- Phase 03: application and microservice onboarding.
--
-- V1 modelled the fleet as it runs: services, clusters and the targets that pair
-- them. It had no notion of where a service came from. These tables add that
-- provenance — a project owns applications, an application is backed by a Git
-- repository, and the microservices inside that repository are discovered from it
-- rather than typed in by hand.

-- =============================== ownership =================================

CREATE TABLE project (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    name        VARCHAR(120) NOT NULL,
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (org_id, name)
);

CREATE TABLE application (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id  UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    name        VARCHAR(120) NOT NULL,
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_id, name)
);

-- One repository per application. The platform deploys what a repository builds,
-- so allowing several would make "which commit is running?" ambiguous.
CREATE TABLE git_repository (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id   UUID NOT NULL UNIQUE REFERENCES application(id) ON DELETE CASCADE,
    provider         VARCHAR(20) NOT NULL DEFAULT 'GITHUB'
                     CHECK (provider IN ('GITHUB','GITLAB','BITBUCKET','OTHER')),
    owner            VARCHAR(200) NOT NULL,
    repo             VARCHAR(200) NOT NULL,
    url              VARCHAR(500) NOT NULL,
    default_branch   VARCHAR(200),
    -- Never the credential itself: a name pointing at whatever secret store the
    -- deployment uses. Secrets management is Phase 15.
    credentials_ref  VARCHAR(255),
    last_synced_at   TIMESTAMPTZ,
    last_sync_status VARCHAR(20) CHECK (last_sync_status IN ('OK','FAILED')),
    last_sync_detail TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ========================= service provenance ==============================

-- V1's service table gains the facts discovery can determine. Every column is
-- nullable: a service registered by hand before any repository is connected is
-- still a valid service.
ALTER TABLE service ADD COLUMN application_id   UUID REFERENCES application(id) ON DELETE SET NULL;
ALTER TABLE service ADD COLUMN source_path      VARCHAR(500);
ALTER TABLE service ADD COLUMN language         VARCHAR(40);
ALTER TABLE service ADD COLUMN build_type       VARCHAR(40);
ALTER TABLE service ADD COLUMN container_port   INT;
ALTER TABLE service ADD COLUMN dockerfile_path  VARCHAR(500);
ALTER TABLE service ADD COLUMN discovery_source VARCHAR(20) NOT NULL DEFAULT 'MANUAL'
                                CHECK (discovery_source IN ('MANUAL','REPOSITORY_SCAN'));

CREATE INDEX idx_service_application ON service (application_id);

-- ========================== deployment shape ===============================

-- What a service asks for when it runs. Kept apart from deployment_target
-- because it is a property of the service everywhere it is deployed, whereas a
-- target's replica count is per-cluster and changes under autoscaling.
CREATE TABLE service_resource (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service_id             UUID NOT NULL UNIQUE REFERENCES service(id) ON DELETE CASCADE,
    cpu_request_millicores INT NOT NULL DEFAULT 100,
    cpu_limit_millicores   INT NOT NULL DEFAULT 500,
    memory_request_mib     INT NOT NULL DEFAULT 128,
    memory_limit_mib       INT NOT NULL DEFAULT 512,
    desired_replicas       INT NOT NULL DEFAULT 1,
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (cpu_limit_millicores >= cpu_request_millicores),
    CHECK (memory_limit_mib >= memory_request_mib)
);

CREATE TABLE service_env_var (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service_id UUID NOT NULL REFERENCES service(id) ON DELETE CASCADE,
    env_key    VARCHAR(200) NOT NULL,
    env_value  TEXT,
    -- Marks a value that must land in a Secret rather than a ConfigMap. The value
    -- column stays null for these until Phase 15 supplies a real secret store —
    -- storing a plaintext secret here to be "complete" would be worse than an
    -- explicit gap.
    is_secret  BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (service_id, env_key)
);

CREATE INDEX idx_env_var_service ON service_env_var (service_id);
