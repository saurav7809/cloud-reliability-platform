-- Section 04: container build and deployment history.
--
-- Two records the platform could not previously keep. A build is how an image
-- comes to exist, and a deployment is a claim about which image is running -
-- without either, "what changed?" during an incident has no answer, and RCA's
-- change-event signal can only see scaling and healing.

CREATE TABLE image_build (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service_id    UUID REFERENCES service(id) ON DELETE SET NULL,
    cluster_id    UUID NOT NULL REFERENCES cluster(id) ON DELETE CASCADE,
    git_url       VARCHAR(500) NOT NULL,
    git_ref       VARCHAR(120) NOT NULL DEFAULT 'main',
    context_path  VARCHAR(255) NOT NULL DEFAULT '.',
    dockerfile    VARCHAR(255) NOT NULL DEFAULT 'Dockerfile',
    image         VARCHAR(500) NOT NULL,
    -- The Kubernetes Job doing the work. Kept so a running build can be followed
    -- and a finished one can have its logs fetched while they still exist.
    job_name      VARCHAR(253),
    status        VARCHAR(20) NOT NULL DEFAULT 'RUNNING'
                  CHECK (status IN ('RUNNING','SUCCEEDED','FAILED')),
    detail        TEXT,
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ
);
CREATE INDEX idx_build_recent ON image_build (started_at DESC);

CREATE TABLE deployment_history (
    id           BIGSERIAL PRIMARY KEY,
    target_id    UUID REFERENCES deployment_target(id) ON DELETE SET NULL,
    cluster_id   UUID NOT NULL REFERENCES cluster(id) ON DELETE CASCADE,
    namespace    VARCHAR(120) NOT NULL,
    workload     VARCHAR(253) NOT NULL,
    image        VARCHAR(500) NOT NULL,
    -- What was running immediately before, so a rollback has somewhere to go
    -- rather than needing a human to remember the previous tag.
    previous_image VARCHAR(500),
    replicas     INT NOT NULL DEFAULT 0,
    env          JSONB NOT NULL DEFAULT '{}'::jsonb,
    actor_id     UUID REFERENCES app_user(id) ON DELETE SET NULL,
    succeeded    BOOLEAN NOT NULL DEFAULT true,
    detail       TEXT,
    deployed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_deployment_history_recent ON deployment_history (workload, deployed_at DESC);
