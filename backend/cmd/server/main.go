package main

import (
	"context"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/aegiscloud/backend/internal/api"
	"github.com/aegiscloud/backend/internal/cache"
	"github.com/aegiscloud/backend/internal/db"
)

func main() {
	ctx := context.Background()

	dsn := envOr("DATABASE_URL",
		"postgres://aegiscloud:aegiscloud@localhost:5432/aegiscloud?sslmode=disable")

	database, err := db.Connect(ctx, dsn)
	if err != nil {
		log.Fatalf("postgres: %v", err)
	}
	defer database.Close()

	if err := database.Migrate(dsn); err != nil {
		log.Fatalf("migrate: %v", err)
	}

	if err := database.Seed(ctx,
		envOr("AEGISCLOUD_ADMIN_EMAIL", "admin@aegiscloud.local"),
		envOr("AEGISCLOUD_ADMIN_PASSWORD", "changeme123"),
	); err != nil {
		log.Fatalf("seed: %v", err)
	}

	// Redis is optional by design — Connect logs and returns a disabled cache
	// rather than failing, so the platform still serves if the cache is down.
	rc := cache.Connect(ctx, os.Getenv("REDIS_ADDR"))
	defer rc.Close()

	srv := &http.Server{
		Addr:              ":" + envOr("PORT", "8080"),
		Handler:           api.NewRouter(api.Deps{Pool: database.Pool, Cache: rc}),
		ReadHeaderTimeout: 10 * time.Second,
	}

	go func() {
		log.Printf("aegiscloud backend listening on %s", srv.Addr)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatal(err)
		}
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	<-stop

	log.Printf("shutting down")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		log.Printf("shutdown: %v", err)
	}
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
