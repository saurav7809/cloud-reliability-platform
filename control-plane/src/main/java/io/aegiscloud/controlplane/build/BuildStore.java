package io.aegiscloud.controlplane.build;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Build records and deployment history — how an image came to exist, and where it went. */
@Repository
public class BuildStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public BuildStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    // -------------------------------------------------------------- builds

    /**
     * Records a build before its Job is created.
     *
     * <p>Written first for the same reason an experiment is: if the platform dies
     * between creating the Job and recording it, a RUNNING row with a job name is
     * what tells an operator a build is loose in the cluster.
     */
    public UUID open(UUID serviceId, UUID clusterId, String gitUrl, String gitRef,
                     String contextPath, String dockerfile, String image, String jobName) {
        return jdbc.queryForObject("""
                INSERT INTO image_build (service_id, cluster_id, git_url, git_ref, context_path,
                                         dockerfile, image, job_name, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'RUNNING') RETURNING id
                """, UUID.class, serviceId, clusterId, gitUrl, gitRef, contextPath,
                dockerfile, image, jobName);
    }

    public void finish(UUID buildId, String status, String detail) {
        jdbc.update("""
                UPDATE image_build SET status = ?, detail = ?, finished_at = now()
                WHERE id = ? AND status = 'RUNNING'
                """, status, detail, buildId);
    }

    public record RunningBuild(UUID id, UUID clusterId, String jobName, String image) {
    }

    public List<RunningBuild> running() {
        return jdbc.query("""
                SELECT id, cluster_id, job_name, image FROM image_build
                WHERE status = 'RUNNING' AND job_name IS NOT NULL
                """, (rs, i) -> new RunningBuild(
                UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)),
                rs.getString(3), rs.getString(4)));
    }

    public record BuildRow(UUID id, String serviceName, String clusterName, String gitUrl,
                           String gitRef, String contextPath, String image, String jobName,
                           String status, String detail, Instant startedAt, Instant finishedAt) {
    }

    private static final String BUILD_SELECT = """
            SELECT b.id, s.name AS service_name, c.name AS cluster_name, b.git_url, b.git_ref,
                   b.context_path, b.image, b.job_name, b.status, b.detail,
                   b.started_at, b.finished_at, b.cluster_id
            FROM image_build b
            JOIN cluster c ON c.id = b.cluster_id
            LEFT JOIN service s ON s.id = b.service_id
            """;

    /** Builds for one organisation, newest first. */
    public List<BuildRow> builds(UUID orgId, int limit) {
        return jdbc.query(BUILD_SELECT + "WHERE c.org_id = ? ORDER BY b.started_at DESC LIMIT ?",
                (rs, i) -> mapBuild(rs), orgId, limit);
    }

    public Optional<BuildRow> build(UUID buildId) {
        return jdbc.query(BUILD_SELECT + "WHERE b.id = ?", (rs, i) -> mapBuild(rs), buildId)
                .stream().findFirst();
    }

    private static BuildRow mapBuild(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new BuildRow(
                UUID.fromString(rs.getString("id")), rs.getString("service_name"),
                rs.getString("cluster_name"), rs.getString("git_url"), rs.getString("git_ref"),
                rs.getString("context_path"), rs.getString("image"), rs.getString("job_name"),
                rs.getString("status"), rs.getString("detail"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("finished_at") == null
                        ? null : rs.getTimestamp("finished_at").toInstant());
    }

    /** The cluster a build ran on, for fetching its Job. */
    public Optional<UUID> clusterOf(UUID buildId) {
        return jdbc.queryForList("SELECT cluster_id FROM image_build WHERE id = ?",
                UUID.class, buildId).stream().findFirst();
    }

    // --------------------------------------------------- deployment history

    /**
     * Records a rollout, including what was running before it.
     *
     * <p>The previous image is captured at deploy time rather than derived later,
     * because "what was running before" stops being answerable the moment the object
     * is overwritten — and that is exactly the question a rollback asks.
     */
    public void recordDeployment(UUID clusterId, String namespace, String workload, String image,
                                 String previousImage, int replicas, Map<String, String> env,
                                 UUID actorId, boolean succeeded, String detail) {
        jdbc.update("""
                INSERT INTO deployment_history (cluster_id, namespace, workload, image,
                                                previous_image, replicas, env, actor_id,
                                                succeeded, detail)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """, clusterId, namespace, workload, image, previousImage, replicas,
                toJson(env), actorId, succeeded, detail);
    }

    public record DeploymentRecord(long id, String clusterName, String namespace, String workload,
                                   String image, String previousImage, int replicas,
                                   Map<String, Object> env, String actor, boolean succeeded,
                                   String detail, Instant deployedAt) {
    }

    /** The select every history read shares. */
    private static final String HISTORY_SELECT = """
            SELECT h.id, c.name AS cluster_name, h.namespace, h.workload, h.image,
                   h.previous_image, h.replicas, h.env, COALESCE(u.email, 'platform') AS actor,
                   h.succeeded, h.detail, h.deployed_at
            FROM deployment_history h
            JOIN cluster c ON c.id = h.cluster_id
            LEFT JOIN app_user u ON u.id = h.actor_id
            """;

    public List<DeploymentRecord> history(UUID orgId, String workload, int limit) {
        // Assembled with leading spaces rather than trailing ones: a Java text block
        // strips trailing whitespace from every line, so a fragment ending in "? "
        // silently loses the space and runs into the next clause as "?AND".
        String sql = HISTORY_SELECT
                + "WHERE c.org_id = ?"
                + (workload == null ? "" : " AND h.workload = ?")
                + " ORDER BY h.deployed_at DESC LIMIT ?";

        Object[] args = workload == null
                ? new Object[]{orgId, limit} : new Object[]{orgId, workload, limit};

        return jdbc.query(sql, (rs, i) -> new DeploymentRecord(
                rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getInt(7), readMap(rs.getString(8)), rs.getString(9),
                rs.getBoolean(10), rs.getString(11), rs.getTimestamp(12).toInstant()), args);
    }

    /**
     * The last image successfully deployed to a workload before the current one.
     *
     * <p>The target of a rollback. Only successful deployments count: rolling back to
     * an image whose own rollout failed would replace one broken state with another.
     */
    public Optional<DeploymentRecord> previousSuccessful(UUID orgId, String workload) {
        List<DeploymentRecord> records = history(orgId, workload, 20).stream()
                .filter(DeploymentRecord::succeeded)
                .toList();

        if (records.isEmpty()) {
            return Optional.empty();
        }

        String current = records.get(0).image();
        return records.stream()
                .filter(record -> !record.image().equals(current))
                .findFirst();
    }

    private String toJson(Map<String, String> value) {
        try {
            return mapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            return Map.of("unparseable", json);
        }
    }
}
