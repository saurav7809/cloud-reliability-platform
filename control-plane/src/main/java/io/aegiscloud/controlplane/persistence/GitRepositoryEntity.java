package io.aegiscloud.controlplane.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.util.UUID;

/**
 * The repository an application is built from.
 *
 * <p>Holds the outcome of the last scan as well as the coordinates, so the console
 * can say why a discovery run found nothing — a private repository without
 * credentials and a repository with no services in it are very different problems.
 */
@Entity
@Table(name = "git_repository")
public class GitRepositoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "application_id", nullable = false)
    private UUID applicationId;

    @Column(nullable = false)
    private String provider = "GITHUB";

    @Column(nullable = false)
    private String owner;

    @Column(nullable = false)
    private String repo;

    @Column(nullable = false)
    private String url;

    @Column(name = "default_branch")
    private String defaultBranch;

    /** A name pointing into a secret store — never the token itself. */
    @Column(name = "credentials_ref")
    private String credentialsRef;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "last_sync_status")
    private String lastSyncStatus;

    @Column(name = "last_sync_detail")
    private String lastSyncDetail;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at")
    private Instant createdAt;

    protected GitRepositoryEntity() {
        // required by JPA
    }

    public GitRepositoryEntity(UUID applicationId, String provider, String owner, String repo,
                               String url, String credentialsRef) {
        this.applicationId = applicationId;
        this.provider = provider;
        this.owner = owner;
        this.repo = repo;
        this.url = url;
        this.credentialsRef = credentialsRef;
    }

    public void recordSuccessfulSync(String defaultBranch, String detail) {
        this.defaultBranch = defaultBranch;
        this.lastSyncedAt = Instant.now();
        this.lastSyncStatus = "OK";
        this.lastSyncDetail = detail;
    }

    public void recordFailedSync(String detail) {
        this.lastSyncedAt = Instant.now();
        this.lastSyncStatus = "FAILED";
        this.lastSyncDetail = detail;
    }

    public UUID getId() {
        return id;
    }

    public UUID getApplicationId() {
        return applicationId;
    }

    public String getProvider() {
        return provider;
    }

    public String getOwner() {
        return owner;
    }

    public String getRepo() {
        return repo;
    }

    public String getUrl() {
        return url;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }

    public String getCredentialsRef() {
        return credentialsRef;
    }

    public Instant getLastSyncedAt() {
        return lastSyncedAt;
    }

    public String getLastSyncStatus() {
        return lastSyncStatus;
    }

    public String getLastSyncDetail() {
        return lastSyncDetail;
    }
}
