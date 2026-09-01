package io.aegiscloud.controlplane.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface GitRepositoryRepository extends JpaRepository<GitRepositoryEntity, UUID> {

    Optional<GitRepositoryEntity> findByApplicationId(UUID applicationId);
}
