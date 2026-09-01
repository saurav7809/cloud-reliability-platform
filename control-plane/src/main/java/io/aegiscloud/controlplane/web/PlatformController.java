package io.aegiscloud.controlplane.web;

import io.aegiscloud.controlplane.auth.Tenant;
import io.aegiscloud.controlplane.domain.Models;
import io.aegiscloud.controlplane.store.PlatformStore;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** The platform read API the operator dashboard is built on, plus alert lifecycle actions. */
@RestController
@RequestMapping("/api/v1")
public class PlatformController {

    private final PlatformStore store;

    public PlatformController(PlatformStore store) {
        this.store = store;
    }

    @GetMapping("/overview")
    public Models.Overview overview() {
        return store.overview(Tenant.currentOrgId());
    }

    @GetMapping("/clusters")
    public List<Models.Cluster> clusters() {
        return store.clusters(Tenant.currentOrgId());
    }

    @GetMapping("/services")
    public List<Models.Service> services() {
        return store.services(Tenant.currentOrgId());
    }

    @GetMapping("/targets")
    public List<Models.DeploymentTarget> targets() {
        return store.targets(Tenant.currentOrgId());
    }

    @GetMapping("/slos")
    public List<Models.Slo> slos() {
        return store.slos(Tenant.currentOrgId());
    }

    @GetMapping("/policies")
    public List<Models.Policy> policies() {
        return store.policies(Tenant.currentOrgId());
    }

    @GetMapping("/control-plane/scaling-events")
    public List<Models.ScalingEvent> scalingEvents() {
        return store.scalingEvents(Tenant.currentOrgId());
    }

    @GetMapping("/control-plane/healing-events")
    public List<Models.HealingEvent> healingEvents() {
        return store.healingEvents(Tenant.currentOrgId());
    }

    @GetMapping("/experiment-runs")
    public List<Models.ExperimentRun> experiments() {
        return store.experiments(Tenant.currentOrgId());
    }

    @GetMapping("/alerts")
    public List<Models.Alert> alerts() {
        return store.alerts(Tenant.currentOrgId());
    }

    /*
     * Alert lifecycle changes are OPERATOR+ actions; VIEWER is read-only. The rule
     * is declared on the handler that performs the mutation, so it is visible in the
     * method it governs rather than in routing configuration read separately.
     */

    @PostMapping("/alerts/{alertId}/acknowledge")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public Map<String, String> acknowledge(@PathVariable String alertId) {
        return setStatus(alertId, Models.AlertStatus.ACKNOWLEDGED);
    }

    @PostMapping("/alerts/{alertId}/resolve")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public Map<String, String> resolve(@PathVariable String alertId) {
        return setStatus(alertId, Models.AlertStatus.RESOLVED);
    }

    private Map<String, String> setStatus(String alertId, Models.AlertStatus status) {
        if (!store.setAlertStatus(Tenant.currentOrgId(), alertId, status)) {
            throw ApiException.notFound("alert " + alertId + " not found");
        }
        return Map.of("id", alertId, "status", status.name());
    }
}
