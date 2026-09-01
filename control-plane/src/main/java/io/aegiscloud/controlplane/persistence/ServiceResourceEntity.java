package io.aegiscloud.controlplane.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * What a service asks for when it runs.
 *
 * <p>Separate from {@code deployment_target} because these are properties of the
 * service everywhere it is deployed, whereas a target's replica count is per-cluster
 * and moves under autoscaling. Requests and limits in the units Kubernetes uses —
 * millicores and MiB — so nothing has to be reinterpreted at manifest-generation time.
 */
@Entity
@Table(name = "service_resource")
public class ServiceResourceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "service_id", nullable = false)
    private UUID serviceId;

    @Column(name = "cpu_request_millicores", nullable = false)
    private int cpuRequestMillicores = 100;

    @Column(name = "cpu_limit_millicores", nullable = false)
    private int cpuLimitMillicores = 500;

    @Column(name = "memory_request_mib", nullable = false)
    private int memoryRequestMib = 128;

    @Column(name = "memory_limit_mib", nullable = false)
    private int memoryLimitMib = 512;

    @Column(name = "desired_replicas", nullable = false)
    private int desiredReplicas = 1;

    protected ServiceResourceEntity() {
        // required by JPA
    }

    public ServiceResourceEntity(UUID serviceId) {
        this.serviceId = serviceId;
    }

    /**
     * Updates the requested shape.
     *
     * <p>The database enforces limit >= request with CHECK constraints; this
     * rejects the same thing earlier so the caller gets a 400 explaining the
     * problem rather than a constraint-violation stack trace.
     */
    public void update(int cpuRequest, int cpuLimit, int memoryRequest, int memoryLimit, int replicas) {
        if (cpuLimit < cpuRequest) {
            throw new IllegalArgumentException(
                    "cpu limit (" + cpuLimit + "m) must be at least the request (" + cpuRequest + "m)");
        }
        if (memoryLimit < memoryRequest) {
            throw new IllegalArgumentException(
                    "memory limit (" + memoryLimit + "Mi) must be at least the request (" + memoryRequest + "Mi)");
        }
        if (replicas < 0) {
            throw new IllegalArgumentException("replicas cannot be negative");
        }
        this.cpuRequestMillicores = cpuRequest;
        this.cpuLimitMillicores = cpuLimit;
        this.memoryRequestMib = memoryRequest;
        this.memoryLimitMib = memoryLimit;
        this.desiredReplicas = replicas;
    }

    public UUID getId() {
        return id;
    }

    public UUID getServiceId() {
        return serviceId;
    }

    public int getCpuRequestMillicores() {
        return cpuRequestMillicores;
    }

    public int getCpuLimitMillicores() {
        return cpuLimitMillicores;
    }

    public int getMemoryRequestMib() {
        return memoryRequestMib;
    }

    public int getMemoryLimitMib() {
        return memoryLimitMib;
    }

    public int getDesiredReplicas() {
        return desiredReplicas;
    }
}
