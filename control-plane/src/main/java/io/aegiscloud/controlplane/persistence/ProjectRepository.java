package io.aegiscloud.controlplane.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<ProjectEntity, UUID> {

    Optional<ProjectEntity> findByOrgIdAndName(UUID orgId, String name);

    List<ProjectEntity> findByOrgId(UUID orgId);
}
