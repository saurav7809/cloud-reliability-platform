package io.aegiscloud.controlplane.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServiceEnvVarRepository extends JpaRepository<ServiceEnvVarEntity, UUID> {

    List<ServiceEnvVarEntity> findByServiceId(UUID serviceId);

    Optional<ServiceEnvVarEntity> findByServiceIdAndEnvKey(UUID serviceId, String envKey);
}
