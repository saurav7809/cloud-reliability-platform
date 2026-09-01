package io.aegiscloud.controlplane.persistence;

import io.aegiscloud.controlplane.domain.Models;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.util.UUID;

/**
 * A registered Kubernetes cluster.
 *
 * <p>Mapped for JPA because the Deployment Engine writes these rows: registering a
 * cluster, then updating status, node count and version each time connectivity is
 * probed. The dashboard's read path still goes through {@code PlatformStore} — this
 * entity exists for the write side, not to replace those queries.
 *
 * <p>{@code kubeconfigRef} names a kube context, which is the whole of the
 * cloud-agnostic boundary: EKS, AKS, GKE and kind differ here by a context name and
 * a provider label, never by a code path.
 */
@Entity
@Table(name = "cluster")
public class ClusterEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_type", nullable = false)
    private Models.ProviderType providerType;

    private String distribution;

    private String region;

    @Column(name = "kubeconfig_ref")
    private String kubeconfigRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Models.ClusterStatus status = Models.ClusterStatus.UNREACHABLE;

    @Column(name = "node_count", nullable = false)
    private int nodeCount;

    @Column(name = "k8s_version")
    private String k8sVersion;

    @Column(name = "is_local", nullable = false)
    private boolean local;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at")
    private Instant createdAt;

    protected ClusterEntity() {
        // required by JPA
    }

    public ClusterEntity(UUID orgId, String name, Models.ProviderType providerType,
                         String distribution, String region, String kubeconfigRef, boolean local) {
        this.orgId = orgId;
        this.name = name;
        this.providerType = providerType;
        this.distribution = distribution;
        this.region = region;
        this.kubeconfigRef = kubeconfigRef;
        this.local = local;
    }

    /** Records the result of a successful connectivity probe. */
    public void markReachable(int nodeCount, String k8sVersion) {
        this.status = Models.ClusterStatus.HEALTHY;
        this.nodeCount = nodeCount;
        this.k8sVersion = k8sVersion;
    }

    /** Records that the cluster could not be reached, leaving the last known facts intact. */
    public void markUnreachable() {
        this.status = Models.ClusterStatus.UNREACHABLE;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public String getName() {
        return name;
    }

    public Models.ProviderType getProviderType() {
        return providerType;
    }

    public String getDistribution() {
        return distribution;
    }

    public String getRegion() {
        return region;
    }

    public String getKubeconfigRef() {
        return kubeconfigRef;
    }

    public Models.ClusterStatus getStatus() {
        return status;
    }

    public int getNodeCount() {
        return nodeCount;
    }

    public String getK8sVersion() {
        return k8sVersion;
    }

    public boolean isLocal() {
        return local;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
