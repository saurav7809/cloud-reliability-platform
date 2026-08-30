package api

import (
	"encoding/json"
	"net/http"
	"time"
)

// Version identifies the running build. Phase 2 pins it to the current phase;
// it becomes a build-stamped value once CI produces release artifacts.
const Version = "v0.2.0"

func HealthHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{
		"status":  "ok",
		"service": "aegiscloud-backend",
		"version": Version,
		"time":    time.Now().UTC(),
	})
}
