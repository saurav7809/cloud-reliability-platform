package io.aegiscloud.controlplane.web;

import io.aegiscloud.controlplane.auth.CurrentUser;
import io.aegiscloud.controlplane.engine.ActionType;
import io.aegiscloud.controlplane.engine.AutonomyLevel;
import io.aegiscloud.controlplane.engine.ControlPlaneEvents;
import io.aegiscloud.controlplane.engine.ControlPlaneStore;
import io.aegiscloud.controlplane.engine.PolicyLimits;
import io.aegiscloud.controlplane.engine.ReconciliationLoop;
import io.aegiscloud.controlplane.persistence.ClusterRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

/**
 * The Control Plane's HTTP surface: what the autonomous loop is permitted to do, the
 * guardrails it works within, and what it has actually done.
 *
 * <p>Changing autonomy or policy is ADMIN-only. Those two settings are the whole of
 * what stands between a suggestion and an unattended write to a production cluster,
 * so they are not an operator-level decision.
 */
@RestController
@RequestMapping("/api/v1/control-plane")
public class ControlPlaneController {

    private final ControlPlaneStore store;
    private final ReconciliationLoop loop;
    private final ClusterRepository clusters;
    private final ControlPlaneEvents events;

    public ControlPlaneController(ControlPlaneStore store, ReconciliationLoop loop,
                                  ClusterRepository clusters, ControlPlaneEvents events) {
        this.store = store;
        this.loop = loop;
        this.clusters = clusters;
        this.events = events;
    }

    /**
     * The live feed of control-loop activity: every decision, action and cycle result
     * as it happens.
     *
     * <p>Served as Server-Sent Events so the dashboard shows the platform acting in
     * real time instead of reconstructing it from periodic reads. Because the browser
     * EventSource cannot send an Authorization header, this route also accepts the
     * token as a {@code token} query parameter — see JwtAuthFilter for why that
     * exception is confined to streams.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return events.subscribe();
    }

    // --------------------------------------------------------------- autonomy

    /** Every cluster and action type with the level actually in force, configured or defaulted. */
    @GetMapping("/autonomy")
    public List<ControlPlaneStore.AutonomySetting> autonomy() {
        return store.autonomySettings();
    }

    public record AutonomyUpdate(
            @NotBlank String clusterId,
            @NotBlank String actionType,
            @NotBlank String level) {
    }

    @PutMapping("/autonomy")
    @PreAuthorize("hasRole('ADMIN')")
    public ControlPlaneStore.AutonomySetting setAutonomy(@Valid @RequestBody AutonomyUpdate update) {
        UUID clusterId = clusterId(update.clusterId());
        ActionType actionType = parse(ActionType.class, update.actionType(), "action type");
        AutonomyLevel level = parse(AutonomyLevel.class, update.level(), "autonomy level");

        store.setLevel(clusterId, actionType, level, UUID.fromString(CurrentUser.get().id()));

        return store.autonomySettings().stream()
                .filter(s -> s.clusterId().equals(clusterId.toString())
                        && s.actionType().equals(actionType.name()))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("cluster " + clusterId + " not found"));
    }

    // --------------------------------------------------------------- policies

    /** The guardrails in force for a cluster, including the ones it inherits. */
    @GetMapping("/policies/{clusterId}")
    public PolicyLimits policy(@PathVariable String clusterId) {
        return store.limitsFor(clusterId(clusterId));
    }

    public record PolicyUpdate(
            @Min(1) int maxReplicas,
            @Min(0) int maxConcurrentExperiments,
            List<String> protectedNamespaces) {
    }

    @PutMapping("/policies/{clusterId}")
    @PreAuthorize("hasRole('ADMIN')")
    public PolicyLimits setPolicy(@PathVariable String clusterId, @Valid @RequestBody PolicyUpdate update) {
        UUID id = clusterId(clusterId);
        PolicyLimits limits = new PolicyLimits(update.maxReplicas(), update.maxConcurrentExperiments(),
                update.protectedNamespaces() == null ? List.of() : update.protectedNamespaces());
        store.savePolicy(id, limits);
        return store.limitsFor(id);
    }

    // ------------------------------------------------------------ the loop

    /**
     * Runs one reconciliation cycle immediately and returns everything it decided.
     *
     * <p>This is the same method the scheduler calls. An operator checking what the
     * platform would do sees the real loop, not a dry-run approximation of it — and
     * at the default SUGGEST autonomy that is already what it does.
     */
    @PostMapping("/reconcile")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ReconciliationLoop.CycleReport reconcile() {
        return loop.reconcile();
    }

    /** The action ledger: what was observed, concluded, permitted, done, and how it turned out. */
    @GetMapping("/actions")
    public List<ControlPlaneStore.ActionRow> actions(@RequestParam(defaultValue = "50") int limit) {
        return store.recentActions(Math.min(Math.max(limit, 1), 500));
    }

    // ---------------------------------------------------------------- helpers

    private UUID clusterId(String raw) {
        UUID id;
        try {
            id = UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("cluster " + raw + " not found");
        }
        if (!clusters.existsById(id)) {
            throw ApiException.notFound("cluster " + raw + " not found");
        }
        return id;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String raw, String what) {
        try {
            return Enum.valueOf(type, raw.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("unknown " + what + ": " + raw + "; expected one of "
                    + java.util.Arrays.toString(type.getEnumConstants()));
        }
    }
}
