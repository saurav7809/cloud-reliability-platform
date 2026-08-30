package api

import (
	"net/http"
	"os"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
	"github.com/go-chi/cors"

	"github.com/aegiscloud/backend/internal/auth"
)

func NewRouter() http.Handler {
	r := chi.NewRouter()
	r.Use(middleware.Logger)
	r.Use(middleware.Recoverer)
	r.Use(cors.Handler(cors.Options{
		AllowedOrigins:   []string{envOr("AEGISCLOUD_WEB_ORIGIN", "http://localhost:5173")},
		AllowedMethods:   []string{"GET", "POST", "PUT", "DELETE", "OPTIONS"},
		AllowedHeaders:   []string{"Authorization", "Content-Type"},
		AllowCredentials: true,
	}))

	store := auth.NewStore()
	seedEmail := envOr("AEGISCLOUD_ADMIN_EMAIL", "admin@aegiscloud.local")
	seedPassword := envOr("AEGISCLOUD_ADMIN_PASSWORD", "changeme123")
	if err := store.Seed(seedEmail, seedPassword, auth.RoleAdmin); err != nil {
		panic(err)
	}

	tokenManager := auth.NewTokenManager(envOr("AEGISCLOUD_JWT_SECRET", "dev-secret-change-me"), 24*time.Hour)
	authHandlers := &AuthHandlers{Store: store, Tokens: tokenManager}

	r.Get("/healthz", HealthHandler)

	r.Route("/api/v1", func(v1 chi.Router) {
		v1.Post("/auth/login", authHandlers.Login)

		v1.Group(func(protected chi.Router) {
			protected.Use(auth.Middleware(tokenManager))
			protected.Get("/auth/me", authHandlers.Me)
		})
	})

	return r
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
