package io.aegiscloud.controlplane.web;

import io.aegiscloud.controlplane.experiment.ExperimentEngine;
import io.aegiscloud.controlplane.experiment.ExperimentStore;
import io.aegiscloud.controlplane.experiment.FaultType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

/**
 * The Experiment Engine's HTTP surface.
 *
 * <p>ADMIN or OPERATOR only, and never autonomous: unlike scaling and healing, chaos
 * has no autonomy level that lets the platform start one by itself. Deliberately
 * breaking production is a decision a person makes.
 */
@RestController
@RequestMapping("/api/v1")
public class ExperimentController {

    private final ExperimentEngine engine;
    private final ExperimentStore store;

    public ExperimentController(ExperimentEngine engine, ExperimentStore store) {
        this.engine = engine;
        this.store = store;
    }

    /**
     * @param magnitude          pods to kill, or replicas to remove. Ignored by
     *                           DEPENDENCY_OUTAGE, which is all-or-nothing by nature
     * @param dependencyTargetId the service to take down, for DEPENDENCY_OUTAGE
     * @param abortIfScoreBelow  the steady-state hypothesis; the fault is undone
     *                           early if the score falls below it
     */
    public record StartExperimentRequest(
            @NotBlank String targetId,
            @NotBlank String faultType,
            @Min(1) int magnitude,
            @Min(1) int durationSeconds,
            String dependencyTargetId,
            Double abortIfScoreBelow) {
    }

    @PostMapping("/experiments")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ExperimentEngine.ExperimentAccepted start(@Valid @RequestBody StartExperimentRequest request) {
        FaultType faultType;
        try {
            faultType = FaultType.valueOf(request.faultType().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("unknown fault type: " + request.faultType()
                    + "; supported: " + Arrays.toString(FaultType.values())
                    + ". Network and resource-pressure faults need Chaos Mesh and are not "
                    + "simulated with something that merely resembles them.");
        }

        try {
            return engine.start(new ExperimentEngine.ExperimentRequest(
                    uuid(request.targetId()), faultType, request.magnitude(),
                    request.durationSeconds(),
                    request.dependencyTargetId() == null ? null : uuid(request.dependencyTargetId()),
                    request.abortIfScoreBelow()));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    /** One experiment, including the fault that was injected and whether it was undone. */
    @GetMapping("/experiments/{runId}")
    public ExperimentStore.ExperimentRow experiment(@PathVariable String runId) {
        return store.experiment(uuid(runId))
                .orElseThrow(() -> ApiException.notFound("experiment " + runId + " not found"));
    }

    private static UUID uuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("not a valid id: " + raw);
        }
    }
}
