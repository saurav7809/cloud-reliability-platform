package api

import (
	"encoding/json"
	"net/http"

	"github.com/go-chi/chi/v5"

	"github.com/aegiscloud/backend/internal/domain"
	"github.com/aegiscloud/backend/internal/store"
)

type PlatformHandlers struct {
	Store *store.Store
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(v)
}

func (h *PlatformHandlers) Overview(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, h.Store.Overview())
}

func (h *PlatformHandlers) Clusters(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, h.Store.Clusters())
}

func (h *PlatformHandlers) Services(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, h.Store.Services())
}

func (h *PlatformHandlers) Targets(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, h.Store.Targets())
}

func (h *PlatformHandlers) Slos(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, h.Store.Slos())
}

func (h *PlatformHandlers) ScalingEvents(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, h.Store.ScalingEvents())
}

func (h *PlatformHandlers) HealingEvents(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, h.Store.HealingEvents())
}

func (h *PlatformHandlers) Policies(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, h.Store.Policies())
}

func (h *PlatformHandlers) Alerts(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, h.Store.Alerts())
}

func (h *PlatformHandlers) Experiments(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, h.Store.Experiments())
}

func (h *PlatformHandlers) AcknowledgeAlert(w http.ResponseWriter, r *http.Request) {
	h.setAlertStatus(w, r, domain.AlertAcknowledged)
}

func (h *PlatformHandlers) ResolveAlert(w http.ResponseWriter, r *http.Request) {
	h.setAlertStatus(w, r, domain.AlertResolved)
}

func (h *PlatformHandlers) setAlertStatus(w http.ResponseWriter, r *http.Request, status domain.AlertStatus) {
	id := chi.URLParam(r, "alertId")
	if !h.Store.SetAlertStatus(id, status) {
		writeError(w, http.StatusNotFound, "NOT_FOUND", "alert "+id+" not found")
		return
	}
	writeJSON(w, map[string]string{"id": id, "status": string(status)})
}
