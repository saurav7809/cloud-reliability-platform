package io.aegiscloud.controlplane.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ServiceResourceRepository extends JpaRepository<ServiceResourceEntity, UUID> {

    Optional<ServiceResourceEntity> findByServiceId(UUID serviceId);
}
