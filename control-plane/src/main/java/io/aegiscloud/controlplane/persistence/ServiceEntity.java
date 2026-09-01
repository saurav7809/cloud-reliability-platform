package io.aegiscloud.controlplane.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * A microservice.
 *
 * <p>V1 treated these as hand-registered inventory. The provenance columns below
 * are filled in when a service is found by scanning a repository, and left null
 * when someone registers one directly — {@code discoverySource} records which
 * happened, so a re-scan knows what it is allowed to overwrite.
 */
@Entity
@Table(name = "service")
public class ServiceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(name = "owner_team")
    private String ownerTeam;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String tags = "{}";

    @Column(name = "application_id")
    private UUID applicationId;

    /** Directory within the repository the service was found in. */
    @Column(name = "source_path")
    private String sourcePath;

    private String language;

    @Column(name = "build_type")
    private String buildType;

    @Column(name = "container_port")
    private Integer containerPort;

    @Column(name = "dockerfile_path")
    private String dockerfilePath;

    @Column(name = "discovery_source", nullable = false)
    private String discoverySource = "MANUAL";

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at")
    private Instant createdAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "updated_at")
    private Instant updatedAt;

    protected ServiceEntity() {
        // required by JPA
    }

    public ServiceEntity(UUID orgId, String name) {
        this.orgId = orgId;
        this.name = name;
    }

    /** Applies what a repository scan determined about this service. */
    public void applyDiscovery(UUID applicationId, String sourcePath, String language,
                               String buildType, Integer containerPort, String dockerfilePath) {
        this.applicationId = applicationId;
        this.sourcePath = sourcePath;
        this.language = language;
        this.buildType = buildType;
        this.containerPort = containerPort;
        this.dockerfilePath = dockerfilePath;
        this.discoverySource = "REPOSITORY_SCAN";
    }

    public void setDescription(String description) {
        this.description = description;
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

    public String getDescription() {
        return description;
    }

    public String getOwnerTeam() {
        return ownerTeam;
    }

    public String getTags() {
        return tags;
    }

    public UUID getApplicationId() {
        return applicationId;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public String getLanguage() {
        return language;
    }

    public String getBuildType() {
        return buildType;
    }

    public Integer getContainerPort() {
        return containerPort;
    }

    public String getDockerfilePath() {
        return dockerfilePath;
    }

    public String getDiscoverySource() {
        return discoverySource;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
