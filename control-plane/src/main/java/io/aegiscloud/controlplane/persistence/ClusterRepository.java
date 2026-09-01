package io.aegiscloud.controlplane.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClusterRepository extends JpaRepository<ClusterEntity, UUID> {

    Optional<ClusterEntity> findByName(String name);

    /**
     * A cluster by name within one organisation.
     *
     * <p>Cluster names are unique per organisation, not globally — two tenants may
     * each have a "prod". Looking one up by name alone therefore returns whichever
     * row the database happened to order first, which is a cross-tenant read waiting
     * to happen.
     */
    Optional<ClusterEntity> findByOrgIdAndName(UUID orgId, String name);

    List<ClusterEntity> findByActiveTrue();

    boolean existsByOrgIdAndName(UUID orgId, String name);
}
