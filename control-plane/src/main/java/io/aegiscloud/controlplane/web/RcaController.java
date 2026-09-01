package io.aegiscloud.controlplane.web;

import io.aegiscloud.controlplane.auth.Tenant;
import io.aegiscloud.controlplane.rca.DiagnosisService;
import io.aegiscloud.controlplane.rca.RcaEngine;
import io.aegiscloud.controlplane.rca.RcaStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Incidents and their diagnoses (FR-27 to FR-30).
 *
 * <p>Everything here reads or records an opinion; nothing acts. Diagnosing is
 * available to any authenticated user because an on-call engineer should not need
 * elevated rights to ask what is broken, and because the operation cannot change
 * anything outside the platform's own tables.
 */
@RestController
@RequestMapping("/api/v1")
public class RcaController {

    private final DiagnosisService diagnosis;
    private final RcaStore store;

    public RcaController(DiagnosisService diagnosis, RcaStore store) {
        this.diagnosis = diagnosis;
        this.store = store;
    }

    /**
     * Diagnoses whatever is degraded right now, opening an incident for it.
     *
     * <p>Returns 200 with an explicit "nothing is degraded" rather than an empty
     * incident: opening one for a healthy fleet would fill the history with noise
     * that later accuracy measurements would have to filter back out.
     */
    @PostMapping("/incidents/diagnose")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public Object diagnose() {
        return diagnosis.diagnoseCurrent(Tenant.currentOrgId())
                .map(d -> (Object) d)
                .orElse(Map.of("status", "no service is currently below the degradation threshold"));
    }

    @GetMapping("/incidents")
    public List<RcaStore.IncidentRow> incidents(@RequestParam(defaultValue = "50") int limit) {
        return store.incidents(Tenant.currentOrgId(), Math.min(Math.max(limit, 1), 500));
    }

    /** One incident with its ranked verdicts and the evidence each cites. */
    @GetMapping("/incidents/{incidentId}")
    public Map<String, Object> incident(@PathVariable String incidentId) {
        UUID id = uuid(incidentId);
        RcaStore.IncidentRow incident = store.incident(Tenant.currentOrgId(), id)
                .orElseThrow(() -> ApiException.notFound("incident " + incidentId + " not found"));

        return Map.of("incident", incident, "verdicts", store.verdicts(id));
    }

    public record JudgementRequest(int rank, @NotBlank String verdict) {
    }

    /** A human marking a verdict correct or incorrect (FR-30). */
    @PostMapping("/incidents/{incidentId}/judge")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public Map<String, Object> judge(@PathVariable String incidentId,
                                     @Valid @RequestBody JudgementRequest request) {
        String verdict = request.verdict().toUpperCase(Locale.ROOT);
        if (!List.of("CORRECT", "INCORRECT").contains(verdict)) {
            throw ApiException.badRequest("verdict must be CORRECT or INCORRECT");
        }

        int updated = store.judge(uuid(incidentId), request.rank(), verdict);
        if (updated == 0) {
            throw ApiException.notFound("no verdict at rank " + request.rank()
                    + " for incident " + incidentId);
        }
        return Map.of("incidentId", incidentId, "rank", request.rank(), "humanVerdict", verdict);
    }

    @PostMapping("/incidents/{incidentId}/resolve")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public Map<String, Object> resolve(@PathVariable String incidentId) {
        store.resolve(uuid(incidentId));
        return Map.of("incidentId", incidentId, "status", "RESOLVED");
    }

    /**
     * Scores RCA against the chaos experiments, whose true cause the platform knows
     * because it caused them.
     *
     * <p>This is the number that decides whether the Intelligence Layer is worth
     * trusting. It is exposed as an endpoint rather than kept in a test so it can be
     * re-measured against real history at any time, not only against fixtures.
     */
    @GetMapping("/rca/accuracy")
    public RcaEngine.Accuracy accuracy(@RequestParam(defaultValue = "50") int limit) {
        return diagnosis.measureAccuracy(Tenant.currentOrgId(), Math.min(Math.max(limit, 1), 200));
    }

    private static UUID uuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("not a valid incident id: " + raw);
        }
    }
}
