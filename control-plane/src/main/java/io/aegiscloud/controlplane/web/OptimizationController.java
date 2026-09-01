package io.aegiscloud.controlplane.web;

import io.aegiscloud.controlplane.auth.CurrentUser;
import io.aegiscloud.controlplane.optimize.OptimizationService;
import io.aegiscloud.controlplane.optimize.RecommendationStore;
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
 * Cost and performance advice (FR-31 to FR-34).
 *
 * <p>Reading advice is open to any authenticated user; applying it is OPERATOR+,
 * because applying is a cluster write however sensible the advice looked.
 */
@RestController
@RequestMapping("/api/v1")
public class OptimizationController {

    private final OptimizationService optimization;
    private final RecommendationStore store;

    public OptimizationController(OptimizationService optimization, RecommendationStore store) {
        this.optimization = optimization;
        this.store = store;
    }

    /**
     * @param status OPEN, APPLIED, DISMISSED or REVERTED; omit for everything.
     *               Applied and dismissed rows are kept deliberately: FR-34 asks for
     *               bad advice to remain visible after someone acted on it.
     */
    @GetMapping("/recommendations")
    public List<RecommendationStore.RecommendationRow> recommendations(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit) {

        String normalised = status == null ? null : status.toUpperCase(Locale.ROOT);
        if (normalised != null
                && !List.of("OPEN", "APPLIED", "DISMISSED", "REVERTED").contains(normalised)) {
            throw ApiException.badRequest("unknown status: " + status);
        }

        return store.recommendations(normalised, Math.min(Math.max(limit, 1), 500));
    }

    /** Re-examines every target now and refreshes the open recommendations. */
    @PostMapping("/recommendations/refresh")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public OptimizationService.AdvisoryReport refresh() {
        return optimization.advise();
    }

    @PostMapping("/recommendations/{id}/apply")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public OptimizationService.ApplyResult apply(@PathVariable String id) {
        try {
            return optimization.apply(uuid(id), UUID.fromString(CurrentUser.get().id()));
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound(e.getMessage());
        } catch (IllegalStateException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    public record DismissRequest(String reason) {
    }

    @PostMapping("/recommendations/{id}/dismiss")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public OptimizationService.ApplyResult dismiss(@PathVariable String id,
                                                   @RequestBody(required = false) DismissRequest request) {
        try {
            return optimization.dismiss(uuid(id), UUID.fromString(CurrentUser.get().id()),
                    request == null ? null : request.reason());
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound(e.getMessage());
        } catch (IllegalStateException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    /**
     * What the open advice is worth in total, and what is being withheld.
     *
     * <p>The withheld figure is reported rather than quietly excluded: an operator
     * should be able to see that the platform found savings it is declining to
     * recommend, and why.
     */
    @GetMapping("/recommendations/summary")
    public Map<String, Object> summary() {
        List<RecommendationStore.RecommendationRow> open = store.recommendations("OPEN", 500);

        double safeSaving = open.stream().filter(RecommendationStore.RecommendationRow::safeToApply)
                .mapToDouble(RecommendationStore.RecommendationRow::estimatedMonthlySavingUsd)
                .filter(saving -> saving > 0).sum();

        double withheldSaving = open.stream()
                .filter(row -> !row.safeToApply())
                .mapToDouble(RecommendationStore.RecommendationRow::estimatedMonthlySavingUsd)
                .filter(saving -> saving > 0).sum();

        return Map.of(
                "openRecommendations", open.size(),
                "safeMonthlySavingUsd", Math.round(safeSaving * 100) / 100.0,
                "withheldMonthlySavingUsd", Math.round(withheldSaving * 100) / 100.0,
                "note", "withheld savings are real but would spend reliability the service "
                        + "cannot currently spare");
    }

    private static UUID uuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("not a valid recommendation id: " + raw);
        }
    }
}
