package io.aegiscloud.controlplane.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aegiscloud.controlplane.auth.AuthenticatedUser;
import io.aegiscloud.controlplane.auth.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The audit trail (FR-42, FR-43).
 *
 * <p>Every mutation is recorded with who did it, what changed, and when. Human and
 * autonomous actions land in the same table with the same shape, distinguished only
 * by {@code actor_kind} — which is the point of FR-43. An audit log where the
 * platform's own actions are recorded differently from a person's is one where
 * "who changed this?" has two answers and neither is complete.
 *
 * <p>Auditing never fails a request. A write that succeeded and an audit row that
 * did not is a gap in the record; a request rolled back because its audit row failed
 * is an outage caused by bookkeeping. The first is recoverable from the logs, so the
 * failure is logged loudly and the request stands.
 */
@Service
public class AuditLog {

    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AuditLog(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /**
     * Records an action taken by the authenticated caller.
     *
     * @param action     what was done, in the platform's own vocabulary
     *                   ("REGISTER_CLUSTER", "SET_AUTONOMY", "APPLY_RECOMMENDATION")
     * @param entityType the table or concept affected
     * @param entityId   the row affected, as text so non-UUID ids still record
     * @param after      the resulting state, or the request that produced it
     */
    public void recordUserAction(String action, String entityType, String entityId,
                                 Map<String, Object> after) {
        try {
            AuthenticatedUser user = CurrentUser.get();
            write(user.orgId(), UUID.fromString(user.id()), "USER", action, entityType,
                    entityId, null, after);
        } catch (Exception e) {
            log.error("AUDIT GAP: could not record {} on {} {}: {}",
                    action, entityType, entityId, e.getMessage());
        }
    }

    /** Records an action taken by the platform itself, with no human behind it. */
    public void recordEngineAction(UUID orgId, String action, String entityType, String entityId,
                                   Map<String, Object> after) {
        try {
            write(orgId, null, "ENGINE", action, entityType, entityId, null, after);
        } catch (Exception e) {
            log.error("AUDIT GAP: could not record engine {} on {} {}: {}",
                    action, entityType, entityId, e.getMessage());
        }
    }

    /** Records a change, keeping what it was as well as what it became. */
    public void recordChange(String action, String entityType, String entityId,
                             Map<String, Object> before, Map<String, Object> after) {
        try {
            AuthenticatedUser user = CurrentUser.get();
            write(user.orgId(), UUID.fromString(user.id()), "USER", action, entityType,
                    entityId, before, after);
        } catch (Exception e) {
            log.error("AUDIT GAP: could not record {} on {} {}: {}",
                    action, entityType, entityId, e.getMessage());
        }
    }

    private void write(UUID orgId, UUID actorId, String actorKind, String action,
                       String entityType, String entityId,
                       Map<String, Object> before, Map<String, Object> after) {
        jdbc.update("""
                INSERT INTO audit_log_entry (org_id, actor_id, actor_kind, action, entity_type,
                                             entity_id, before_state, after_state)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                """, orgId, actorId, actorKind, action, entityType, entityId,
                before == null ? null : toJson(before),
                after == null ? null : toJson(after));
    }

    public record AuditEntry(long id, String actorKind, String actorEmail, String action,
                             String entityType, String entityId, Map<String, Object> before,
                             Map<String, Object> after, Instant at) {
    }

    /**
     * The trail for one organisation.
     *
     * <p>Scoped like everything else: an audit log is the most sensitive read in the
     * platform, since it describes exactly what a tenant has been doing.
     */
    public List<AuditEntry> entries(UUID orgId, int limit) {
        return jdbc.query("""
                SELECT e.id, e.actor_kind, COALESCE(u.email, 'platform') AS actor,
                       e.action, e.entity_type, e.entity_id, e.before_state, e.after_state,
                       e.created_at
                FROM audit_log_entry e
                LEFT JOIN app_user u ON u.id = e.actor_id
                WHERE e.org_id = ?
                ORDER BY e.created_at DESC
                LIMIT ?
                """, (rs, i) -> new AuditEntry(
                rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), readMap(rs.getString(7)),
                readMap(rs.getString(8)), rs.getTimestamp(9).toInstant()), orgId, limit);
    }

    private String toJson(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{\"unserialisable\":true}";
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
