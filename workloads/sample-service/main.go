// Command sample-service is a deliberately failable workload used to exercise
// AegisCloud end to end.
//
// A healthy container proves nothing about a control plane: Auto-Scaling,
// Self-Healing, SLO burn and chaos experiments are all invisible against a
// service that never misbehaves. This one misbehaves on request — it can burn
// CPU, inject latency, return errors, leak memory until the kernel OOM-kills it,
// and crash outright.
//
// The chaos endpoints live here in the workload, never in the platform.
// AegisCloud stays a pure observer and controller, as the Phase 1 design says.
//
// Deliberately zero dependencies, so the image is a few MB and builds anywhere.
package main

import (
	"fmt"
	"log"
	"math"
	"math/rand"
	"net/http"
	"os"
	"strconv"
	"sync"
	"sync/atomic"
	"time"
)

type state struct {
	mu sync.RWMutex

	// Chaos knobs.
	latencyMs   int
	errorRatePct int
	unready     bool
	leaked      [][]byte

	// Metrics.
	requests    atomic.Int64
	errors      atomic.Int64
	latencySum  atomic.Int64 // milliseconds
	startedAt   time.Time
}

var (
	st          = &state{startedAt: time.Now()}
	serviceName = envOr("SERVICE_NAME", "sample-service")
)

func main() {
	port := envOr("PORT", "8080")

	// Optional startup chaos, so a target can be seeded unhealthy from its manifest.
	if v := intEnv("CHAOS_LATENCY_MS"); v > 0 {
		st.latencyMs = v
		log.Printf("startup chaos: latency=%dms", v)
	}
	if v := intEnv("CHAOS_ERROR_RATE_PCT"); v > 0 {
		st.errorRatePct = v
		log.Printf("startup chaos: errorRate=%d%%", v)
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", handleHealth)
	mux.HandleFunc("/metrics", handleMetrics)
	mux.HandleFunc("/api/work", instrument(handleWork))
	mux.HandleFunc("/chaos/latency", handleLatency)
	mux.HandleFunc("/chaos/error-rate", handleErrorRate)
	mux.HandleFunc("/chaos/unready", handleUnready)
	mux.HandleFunc("/chaos/leak", handleLeak)
	mux.HandleFunc("/chaos/crash", handleCrash)
	mux.HandleFunc("/chaos/reset", handleReset)
	mux.HandleFunc("/", handleRoot)

	log.Printf("%s listening on :%s", serviceName, port)
	if err := http.ListenAndServe(":"+port, mux); err != nil {
		log.Fatal(err)
	}
}

// instrument applies the injected latency and error rate, and records metrics.
// Every user-facing request passes through here, so chaos affects exactly what
// AegisCloud's probes and SLOs measure.
func instrument(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		st.mu.RLock()
		latency, errRate := st.latencyMs, st.errorRatePct
		st.mu.RUnlock()

		if latency > 0 {
			time.Sleep(time.Duration(latency) * time.Millisecond)
		}

		st.requests.Add(1)

		if errRate > 0 && rand.Intn(100) < errRate {
			st.errors.Add(1)
			st.latencySum.Add(time.Since(start).Milliseconds())
			http.Error(w, `{"error":"INJECTED_FAILURE"}`, http.StatusInternalServerError)
			return
		}

		next(w, r)
		st.latencySum.Add(time.Since(start).Milliseconds())
	}
}

func handleRoot(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"service": serviceName,
		"uptime":  time.Since(st.startedAt).Round(time.Second).String(),
		"endpoints": []string{
			"GET  /healthz",
			"GET  /metrics",
			"GET  /api/work?cpu=<ms>",
			"POST /chaos/latency?ms=<n>",
			"POST /chaos/error-rate?pct=<n>",
			"POST /chaos/unready?on=<true|false>",
			"POST /chaos/leak?mb=<n>",
			"POST /chaos/crash",
			"POST /chaos/reset",
		},
	})
}

// handleHealth backs the Kubernetes readiness probe. Flipping it unready makes
// the pod drop out of the Service's endpoints without dying — a softer failure
// than a crash, and a distinct case for Self-Healing to observe.
func handleHealth(w http.ResponseWriter, r *http.Request) {
	st.mu.RLock()
	unready := st.unready
	st.mu.RUnlock()

	if unready {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{
			"status": "unready", "service": serviceName,
		})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"status": "ok", "service": serviceName,
	})
}

// handleWork burns CPU for the requested duration, which is what drives a
// CPU-based Auto-Scaling strategy to add replicas.
func handleWork(w http.ResponseWriter, r *http.Request) {
	ms := intParam(r, "cpu", 50)
	if ms > 5000 {
		ms = 5000 // keep a stray request from pinning a core on a 1-node kind cluster
	}

	deadline := time.Now().Add(time.Duration(ms) * time.Millisecond)
	var acc float64
	for time.Now().Before(deadline) {
		for i := 0; i < 20000; i++ {
			acc += math.Sqrt(float64(i))
		}
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"service": serviceName, "cpuMs": ms, "checksum": int64(acc) % 1000,
	})
}

func handleLatency(w http.ResponseWriter, r *http.Request) {
	ms := intParam(r, "ms", 0)
	st.mu.Lock()
	st.latencyMs = ms
	st.mu.Unlock()
	log.Printf("chaos: latency set to %dms", ms)
	writeJSON(w, http.StatusOK, map[string]any{"latencyMs": ms})
}

func handleErrorRate(w http.ResponseWriter, r *http.Request) {
	pct := intParam(r, "pct", 0)
	if pct < 0 {
		pct = 0
	}
	if pct > 100 {
		pct = 100
	}
	st.mu.Lock()
	st.errorRatePct = pct
	st.mu.Unlock()
	log.Printf("chaos: error rate set to %d%%", pct)
	writeJSON(w, http.StatusOK, map[string]any{"errorRatePct": pct})
}

func handleUnready(w http.ResponseWriter, r *http.Request) {
	on := r.URL.Query().Get("on") != "false"
	st.mu.Lock()
	st.unready = on
	st.mu.Unlock()
	log.Printf("chaos: unready=%v", on)
	writeJSON(w, http.StatusOK, map[string]any{"unready": on})
}

// handleLeak allocates and retains memory. Past the container's memory limit the
// kernel OOM-kills the pod, producing a real OOMKilled healing event rather than
// a simulated one.
func handleLeak(w http.ResponseWriter, r *http.Request) {
	mb := intParam(r, "mb", 32)
	if mb > 512 {
		mb = 512
	}

	block := make([]byte, mb*1024*1024)
	for i := range block {
		block[i] = byte(i % 251) // touch every page so it is really resident
	}

	st.mu.Lock()
	st.leaked = append(st.leaked, block)
	total := 0
	for _, b := range st.leaked {
		total += len(b) / (1024 * 1024)
	}
	st.mu.Unlock()

	log.Printf("chaos: leaked %dMB (total %dMB)", mb, total)
	writeJSON(w, http.StatusOK, map[string]any{"leakedMb": mb, "totalLeakedMb": total})
}

// handleCrash exits non-zero, which sends the pod into CrashLoopBackOff for
// Self-Healing to detect and restart.
func handleCrash(w http.ResponseWriter, r *http.Request) {
	log.Printf("chaos: crashing on request")
	writeJSON(w, http.StatusOK, map[string]any{"crashing": true})

	go func() {
		time.Sleep(100 * time.Millisecond) // let the response flush first
		os.Exit(1)
	}()
}

func handleReset(w http.ResponseWriter, r *http.Request) {
	st.mu.Lock()
	st.latencyMs, st.errorRatePct, st.unready = 0, 0, false
	st.leaked = nil
	st.mu.Unlock()
	log.Printf("chaos: reset")
	writeJSON(w, http.StatusOK, map[string]any{"reset": true})
}

// handleMetrics emits Prometheus text format by hand, keeping the binary
// dependency-free. This is what the Evaluation Engine scrapes in Phase 5.
func handleMetrics(w http.ResponseWriter, r *http.Request) {
	st.mu.RLock()
	latency, errRate, unready := st.latencyMs, st.errorRatePct, st.unready
	leakedMb := 0
	for _, b := range st.leaked {
		leakedMb += len(b) / (1024 * 1024)
	}
	st.mu.RUnlock()

	reqs := st.requests.Load()
	errs := st.errors.Load()
	sum := st.latencySum.Load()

	ready := 1
	if unready {
		ready = 0
	}

	w.Header().Set("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
	l := fmt.Sprintf("service=%q", serviceName)

	fmt.Fprintf(w, "# HELP app_requests_total Total requests served.\n")
	fmt.Fprintf(w, "# TYPE app_requests_total counter\n")
	fmt.Fprintf(w, "app_requests_total{%s} %d\n", l, reqs)

	fmt.Fprintf(w, "# HELP app_errors_total Total failed requests.\n")
	fmt.Fprintf(w, "# TYPE app_errors_total counter\n")
	fmt.Fprintf(w, "app_errors_total{%s} %d\n", l, errs)

	fmt.Fprintf(w, "# HELP app_request_duration_ms_sum Cumulative request duration.\n")
	fmt.Fprintf(w, "# TYPE app_request_duration_ms_sum counter\n")
	fmt.Fprintf(w, "app_request_duration_ms_sum{%s} %d\n", l, sum)

	fmt.Fprintf(w, "# HELP app_ready Readiness: 1 ready, 0 unready.\n")
	fmt.Fprintf(w, "# TYPE app_ready gauge\n")
	fmt.Fprintf(w, "app_ready{%s} %d\n", l, ready)

	fmt.Fprintf(w, "# HELP app_chaos_latency_ms Injected latency per request.\n")
	fmt.Fprintf(w, "# TYPE app_chaos_latency_ms gauge\n")
	fmt.Fprintf(w, "app_chaos_latency_ms{%s} %d\n", l, latency)

	fmt.Fprintf(w, "# HELP app_chaos_error_rate_pct Injected error rate.\n")
	fmt.Fprintf(w, "# TYPE app_chaos_error_rate_pct gauge\n")
	fmt.Fprintf(w, "app_chaos_error_rate_pct{%s} %d\n", l, errRate)

	fmt.Fprintf(w, "# HELP app_leaked_mb Memory intentionally retained.\n")
	fmt.Fprintf(w, "# TYPE app_leaked_mb gauge\n")
	fmt.Fprintf(w, "app_leaked_mb{%s} %d\n", l, leakedMb)

	fmt.Fprintf(w, "# HELP app_uptime_seconds Seconds since process start.\n")
	fmt.Fprintf(w, "# TYPE app_uptime_seconds gauge\n")
	fmt.Fprintf(w, "app_uptime_seconds{%s} %d\n", l, int(time.Since(st.startedAt).Seconds()))
}

/* ------------------------------- helpers -------------------------------- */

func writeJSON(w http.ResponseWriter, status int, v map[string]any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	fmt.Fprint(w, toJSON(v))
}

// toJSON is a tiny encoder covering the shapes used above, avoiding the reflect
// cost of encoding/json for what is a handful of flat objects.
func toJSON(v map[string]any) string {
	out := "{"
	first := true
	for k, val := range v {
		if !first {
			out += ","
		}
		first = false
		out += strconv.Quote(k) + ":"
		switch t := val.(type) {
		case string:
			out += strconv.Quote(t)
		case int:
			out += strconv.Itoa(t)
		case int64:
			out += strconv.FormatInt(t, 10)
		case bool:
			out += strconv.FormatBool(t)
		case []string:
			out += "["
			for i, s := range t {
				if i > 0 {
					out += ","
				}
				out += strconv.Quote(s)
			}
			out += "]"
		default:
			out += strconv.Quote(fmt.Sprint(t))
		}
	}
	return out + "}"
}

func intParam(r *http.Request, name string, def int) int {
	if v := r.URL.Query().Get(name); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return def
}

func intEnv(key string) int {
	if v := os.Getenv(key); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return 0
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
