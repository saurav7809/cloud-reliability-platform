-- Phase 7 discovers dependencies a third way.
--
-- A DEPENDENCY_OUTAGE experiment takes one service down and watches which others
-- degrade. That is a stronger signal than a trace: a trace shows that A called B,
-- while an experiment shows that A stops working when B does - which is what a
-- dependency edge is actually claiming. The two are not the same, and an edge
-- found by breaking something on purpose deserves to be distinguishable from one
-- inferred by watching traffic.
ALTER TABLE service_dependency
    DROP CONSTRAINT service_dependency_discovery_source_check;

ALTER TABLE service_dependency
    ADD CONSTRAINT service_dependency_discovery_source_check
    CHECK (discovery_source IN ('TRACE', 'MANUAL', 'EXPERIMENT'));

-- Edges are read by service and by direction on every graph build; without this
-- the callee lookup is a sequential scan of the whole table.
CREATE INDEX IF NOT EXISTS idx_dependency_callee ON service_dependency (callee_service_id);
