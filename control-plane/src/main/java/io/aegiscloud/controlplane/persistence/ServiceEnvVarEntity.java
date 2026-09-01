package io.aegiscloud.controlplane.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * One environment variable a service is configured with.
 *
 * <p>{@code secret} marks a value destined for a Kubernetes Secret rather than a
 * ConfigMap. The value is deliberately left null for those until a real secret store
 * exists in Phase 15: writing a plaintext credential into this table so the feature
 * looks finished would be a security hole dressed up as completeness.
 */
@Entity
@Table(name = "service_env_var")
public class ServiceEnvVarEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "service_id", nullable = false)
    private UUID serviceId;

    @Column(name = "env_key", nullable = false)
    private String envKey;

    @Column(name = "env_value")
    private String envValue;

    @Column(name = "is_secret", nullable = false)
    private boolean secret;

    protected ServiceEnvVarEntity() {
        // required by JPA
    }

    public ServiceEnvVarEntity(UUID serviceId, String envKey, String envValue, boolean secret) {
        this.serviceId = serviceId;
        this.envKey = envKey;
        this.secret = secret;
        this.envValue = secret ? null : envValue;
    }

    public void setValue(String envValue, boolean secret) {
        this.secret = secret;
        this.envValue = secret ? null : envValue;
    }

    public UUID getId() {
        return id;
    }

    public UUID getServiceId() {
        return serviceId;
    }

    public String getEnvKey() {
        return envKey;
    }

    public String getEnvValue() {
        return envValue;
    }

    public boolean isSecret() {
        return secret;
    }
}
