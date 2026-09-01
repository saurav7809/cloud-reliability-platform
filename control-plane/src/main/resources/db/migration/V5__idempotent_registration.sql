-- Registering a service twice must be a repeat of one statement, not a second
-- service. Without these constraints a re-registration silently doubled the
-- probe rate against a workload, gave it two of every objective, and had the
-- alerting sweep evaluate each of them - so the platform's own measurements
-- became a function of how many times somebody clicked Register.

-- Collapse existing duplicates, keeping the oldest of each pair. The oldest is
-- the one whose samples and budget snapshots are already attached to it.
DELETE FROM endpoint e
USING endpoint keep
WHERE e.target_id = keep.target_id
  AND e.address = keep.address
  AND e.id > keep.id;

DELETE FROM slo s
USING slo keep
WHERE s.target_id = keep.target_id
  AND s.sli_type = keep.sli_type
  AND s.id > keep.id;

CREATE UNIQUE INDEX uq_endpoint_target_address ON endpoint (target_id, address);

-- One objective per SLI type per target. Two availability objectives on one
-- service is not a stricter promise, it is two answers to the same question.
CREATE UNIQUE INDEX uq_slo_target_sli ON slo (target_id, sli_type);
