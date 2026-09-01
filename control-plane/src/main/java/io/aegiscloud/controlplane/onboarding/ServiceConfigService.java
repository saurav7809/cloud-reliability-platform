package io.aegiscloud.controlplane.onboarding;

import io.aegiscloud.controlplane.persistence.ServiceEnvVarEntity;
import io.aegiscloud.controlplane.persistence.ServiceEnvVarRepository;
import io.aegiscloud.controlplane.persistence.ServiceRepository;
import io.aegiscloud.controlplane.persistence.ServiceResourceEntity;
import io.aegiscloud.controlplane.persistence.ServiceResourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Per-service deployment configuration: resource requests and environment variables. */
@Service
public class ServiceConfigService {

    private final ServiceRepository services;
    private final ServiceResourceRepository resources;
    private final ServiceEnvVarRepository envVars;

    public ServiceConfigService(ServiceRepository services, ServiceResourceRepository resources,
                                ServiceEnvVarRepository envVars) {
        this.services = services;
        this.resources = resources;
        this.envVars = envVars;
    }

    /**
     * Returns the service's resource row, creating a default one if it predates the
     * onboarding schema. Services registered before Phase 03 have no row, and a
     * missing one should read as "the defaults" rather than as an error.
     */
    @Transactional
    public ServiceResourceEntity resourcesFor(UUID serviceId) {
        requireService(serviceId);
        return resources.findByServiceId(serviceId)
                .orElseGet(() -> resources.save(new ServiceResourceEntity(serviceId)));
    }

    @Transactional
    public ServiceResourceEntity updateResources(UUID serviceId, int cpuRequest, int cpuLimit,
                                                 int memoryRequest, int memoryLimit, int replicas) {
        ServiceResourceEntity entity = resourcesFor(serviceId);
        entity.update(cpuRequest, cpuLimit, memoryRequest, memoryLimit, replicas);
        return resources.save(entity);
    }

    public List<ServiceEnvVarEntity> envVarsFor(UUID serviceId) {
        requireService(serviceId);
        return envVars.findByServiceId(serviceId);
    }

    /** Sets or replaces one variable. */
    @Transactional
    public ServiceEnvVarEntity setEnvVar(UUID serviceId, String key, String value, boolean secret) {
        requireService(serviceId);
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("environment variable name cannot be blank");
        }

        return envVars.findByServiceIdAndEnvKey(serviceId, key)
                .map(existing -> {
                    existing.setValue(value, secret);
                    return envVars.save(existing);
                })
                .orElseGet(() -> envVars.save(new ServiceEnvVarEntity(serviceId, key, value, secret)));
    }

    @Transactional
    public boolean deleteEnvVar(UUID serviceId, String key) {
        return envVars.findByServiceIdAndEnvKey(serviceId, key)
                .map(existing -> {
                    envVars.delete(existing);
                    return true;
                })
                .orElse(false);
    }

    private void requireService(UUID serviceId) {
        if (!services.existsById(serviceId)) {
            throw new IllegalArgumentException("no such service: " + serviceId);
        }
    }
}
