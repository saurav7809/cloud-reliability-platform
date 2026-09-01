package io.aegiscloud.controlplane.persistence;

import io.aegiscloud.controlplane.domain.Models;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Writes {@code deployment_target} rows: the act of putting a running workload under
 * the platform's management.
 *
 * <p>Every engine works from these rows — scaling, healing, evaluation, experiments
 * and the graph all start by asking which targets exist. A workload with no row here
 * is deployed but unmanaged, which is a legitimate state and not the same as absent.
 */
@Repository
public class TargetRegistry {

    private final JdbcTemplate jdbc;

    public TargetRegistry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Creates a service, or returns the existing one with that name.
     *
     * <p>Idempotent on (organisation, name) because the schema already declares that
     * pair unique: declaring the same service twice is a repeat of one statement,
     * not a second service.
     */
    public UUID createService(UUID orgId, String name, String description, String ownerTeam) {
        return jdbc.queryForObject("""
                INSERT INTO service (org_id, name, description, owner_team)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (org_id, name) DO UPDATE SET
                    description = COALESCE(EXCLUDED.description, service.description),
                    owner_team = COALESCE(EXCLUDED.owner_team, service.owner_team),
                    updated_at = now()
                RETURNING id
                """, UUID.class, orgId, name, description, ownerTeam);
    }

    public Optional<String> serviceName(UUID serviceId) {
        return jdbc.queryForList("SELECT name FROM service WHERE id = ?", String.class, serviceId)
                .stream().findFirst();
    }

    /**
     * Registers a target, or returns the existing one.
     *
     * <p>Idempotent on (service, cluster, namespace) because that triple is what the
     * schema already declares unique: registering the same workload twice is a repeat
     * of the same statement, not a second target.
     */
    public UUID register(UUID serviceId, UUID clusterId, String namespace, String label,
                         Models.ScalingStrategy strategy, int replicas) {
        return jdbc.queryForObject("""
                INSERT INTO deployment_target (service_id, cluster_id, namespace, label,
                                               scaling_strategy, deployment_status,
                                               replicas, desired_replicas)
                VALUES (?, ?, ?, ?, ?, 'HEALTHY', ?, ?)
                ON CONFLICT (service_id, cluster_id, namespace) DO UPDATE SET
                    label = COALESCE(EXCLUDED.label, deployment_target.label),
                    scaling_strategy = EXCLUDED.scaling_strategy,
                    replicas = EXCLUDED.replicas,
                    desired_replicas = EXCLUDED.desired_replicas,
                    is_active = true
                RETURNING id
                """, UUID.class, serviceId, clusterId, namespace, label, strategy.name(),
                replicas, replicas);
    }
}
