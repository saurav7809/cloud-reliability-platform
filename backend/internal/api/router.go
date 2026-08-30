package api

import (
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
	"github.com/go-chi/cors"

	"github.com/aegiscloud/backend/internal/auth"
	"github.com/aegiscloud/backend/internal/store"
)

func NewRouter() http.Handler {
	r := chi.NewRouter()
	r.Use(middleware.Logger)
	r.Use(middleware.Recoverer)
	r.Use(cors.Handler(cors.Options{
		AllowedOrigins:   allowedOrigins(),
		AllowedMethods:   []string{"GET", "POST", "PUT", "DELETE", "OPTIONS"},
		AllowedHeaders:   []string{"Authorization", "Content-Type"},
		AllowCredentials: true,
	}))

	userStore := auth.NewStore()
	seedEmail := envOr("AEGISCLOUD_ADMIN_EMAIL", "admin@aegiscloud.local")
	seedPassword := envOr("AEGISCLOUD_ADMIN_PASSWORD", "changeme123")
	if err := userStore.Seed(seedEmail, seedPassword, auth.RoleAdmin); err != nil {
		panic(err)
	}

	tokenManager := auth.NewTokenManager(envOr("AEGISCLOUD_JWT_SECRET", "dev-secret-change-me"), 24*time.Hour)
	authHandlers := &AuthHandlers{Store: userStore, Tokens: tokenManager}
	platform := &PlatformHandlers{Store: store.New()}

	r.Get("/", IndexHandler)
	r.Get("/healthz", HealthHandler)

	r.Route("/api/v1", func(v1 chi.Router) {
		v1.Post("/auth/login", authHandlers.Login)

		v1.Group(func(p chi.Router) {
			p.Use(auth.Middleware(tokenManager))

			p.Get("/auth/me", authHandlers.Me)

			p.Get("/overview", platform.Overview)
			p.Get("/clusters", platform.Clusters)
			p.Get("/services", platform.Services)
			p.Get("/targets", platform.Targets)
			p.Get("/slos", platform.Slos)
			p.Get("/policies", platform.Policies)
			p.Get("/control-plane/scaling-events", platform.ScalingEvents)
			p.Get("/control-plane/healing-events", platform.HealingEvents)
			p.Get("/experiment-runs", platform.Experiments)
			p.Get("/alerts", platform.Alerts)

			// Alert lifecycle changes are OPERATOR+ actions (VIEWER is read-only).
			p.Group(func(rw chi.Router) {
				rw.Use(auth.RequireRole(auth.RoleAdmin, auth.RoleOperator))
				rw.Post("/alerts/{alertId}/acknowledge", platform.AcknowledgeAlert)
				rw.Post("/alerts/{alertId}/resolve", platform.ResolveAlert)
			})
		})
	})

	return r
}

// allowedOrigins reads a comma-separated origin list so a deployed frontend can be
// added without a code change.
func allowedOrigins() []string {
	raw := envOr("AEGISCLOUD_WEB_ORIGIN", "http://localhost:5173")
	parts := strings.Split(raw, ",")
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		if t := strings.TrimSpace(p); t != "" {
			out = append(out, t)
		}
	}
	return out
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
