package api

import (
	"encoding/json"
	"log"
	"net/http"

	"github.com/go-chi/chi/v5"

	"github.com/aegiscloud/backend/internal/store"
)

type PlatformHandlers struct {
	Store *store.Store
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(v)
}

// serve runs a store query and writes it, mapping any failure to a 500 without
// leaking the SQL error to the client.
func serve[T any](w http.ResponseWriter, r *http.Request, fn func() (T, error)) {
	result, err := fn()
	if err != nil {
		log.Printf("query failed on %s: %v", r.URL.Path, err)
		writeError(w, http.StatusInternalServerError, "INTERNAL", "could not load data")
		return
	}
	writeJSON(w, result)
}

func (h *PlatformHandlers) Overview(w http.ResponseWriter, r *http.Request) {
	serve(w, r, func() (any, error) { return h.Store.Overview(r.Context()) })
}

func (h *PlatformHandlers) Clusters(w http.ResponseWriter, r *http.Request) {
	serve(w, r, func() (any, error) { return h.Store.Clusters(r.Context()) })
}

func (h *PlatformHandlers) Services(w http.ResponseWriter, r *http.Request) {
	serve(w, r, func() (any, error) { return h.Store.Services(r.Context()) })
}

func (h *PlatformHandlers) Targets(w http.ResponseWriter, r *http.Request) {
	serve(w, r, func() (any, error) { return h.Store.Targets(r.Context()) })
}

func (h *PlatformHandlers) Slos(w http.ResponseWriter, r *http.Request) {
	serve(w, r, func() (any, error) { return h.Store.Slos(r.Context()) })
}

func (h *PlatformHandlers) ScalingEvents(w http.ResponseWriter, r *http.Request) {
	serve(w, r, func() (any, error) { return h.Store.ScalingEvents(r.Context()) })
}

func (h *PlatformHandlers) HealingEvents(w http.ResponseWriter, r *http.Request) {
	serve(w, r, func() (any, error) { return h.Store.HealingEvents(r.Context()) })
}

func (h *PlatformHandlers) Policies(w http.ResponseWriter, r *http.Request) {
	serve(w, r, func() (any, error) { return h.Store.Policies(r.Context()) })
}

func (h *PlatformHandlers) Alerts(w http.ResponseWriter, r *http.Request) {
	serve(w, r, func() (any, error) { return h.Store.Alerts(r.Context()) })
}

func (h *PlatformHandlers) Experiments(w http.ResponseWriter, r *http.Request) {
	serve(w, r, func() (any, error) { return h.Store.Experiments(r.Context()) })
}

func (h *PlatformHandlers) AcknowledgeAlert(w http.ResponseWriter, r *http.Request) {
	h.setAlertStatus(w, r, "ACKNOWLEDGED")
}

func (h *PlatformHandlers) ResolveAlert(w http.ResponseWriter, r *http.Request) {
	h.setAlertStatus(w, r, "RESOLVED")
}

func (h *PlatformHandlers) setAlertStatus(w http.ResponseWriter, r *http.Request, status string) {
	id := chi.URLParam(r, "alertId")

	found, err := h.Store.SetAlertStatus(r.Context(), id, status)
	if err != nil {
		log.Printf("alert update failed: %v", err)
		writeError(w, http.StatusInternalServerError, "INTERNAL", "could not update alert")
		return
	}
	if !found {
		writeError(w, http.StatusNotFound, "NOT_FOUND", "alert "+id+" not found")
		return
	}
	writeJSON(w, map[string]string{"id": id, "status": status})
}
