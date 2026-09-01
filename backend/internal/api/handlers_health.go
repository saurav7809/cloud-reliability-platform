package api

import (
	"context"
	"encoding/json"
	"net/http"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/aegiscloud/backend/internal/cache"
)

// Version identifies the running build. Phase 2 pins it to the current phase;
// it becomes a build-stamped value once CI produces release artifacts.
const Version = "v0.2.0"

type HealthHandlers struct {
	Pool  *pgxpool.Pool
	Cache *cache.Cache
}

// Health reports dependency status. PostgreSQL is required, so losing it makes
// the service unhealthy (503). Redis is optional — its absence is reported but
// does not fail the check, matching how the cache degrades in practice.
func (h *HealthHandlers) Health(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
	defer cancel()

	dbStatus := "up"
	healthy := true
	if err := h.Pool.Ping(ctx); err != nil {
		dbStatus = "down"
		healthy = false
	}

	redisStatus := "disabled"
	if h.Cache.Enabled() {
		redisStatus = "up"
	}

	status := "ok"
	code := http.StatusOK
	if !healthy {
		status = "degraded"
		code = http.StatusServiceUnavailable
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(map[string]any{
		"status":  status,
		"service": "aegiscloud-backend",
		"version": Version,
		"time":    time.Now().UTC(),
		"dependencies": map[string]string{
			"postgres": dbStatus,
			"redis":    redisStatus,
		},
	})
}
