package io.aegiscloud.controlplane.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClusterRepository extends JpaRepository<ClusterEntity, UUID> {

    Optional<ClusterEntity> findByName(String name);

    List<ClusterEntity> findByActiveTrue();

    boolean existsByOrgIdAndName(UUID orgId, String name);
}
