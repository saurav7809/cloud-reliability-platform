package api

import (
	"html/template"
	"net/http"
)

type endpoint struct {
	Method string
	Path   string
	Desc   string
	Auth   string
}

var endpointGroups = []struct {
	Name      string
	Endpoints []endpoint
}{
	{"Public", []endpoint{
		{"GET", "/healthz", "Liveness / readiness probe", "none"},
		{"POST", "/api/v1/auth/login", "Exchange email + password for a JWT", "none"},
	}},
	{"Identity", []endpoint{
		{"GET", "/api/v1/auth/me", "Current user profile and role", "bearer"},
	}},
	{"Registry", []endpoint{
		{"GET", "/api/v1/overview", "Fleet rollup powering the dashboard", "bearer"},
		{"GET", "/api/v1/clusters", "Registered clusters (EKS / AKS / GKE / kind)", "bearer"},
		{"GET", "/api/v1/services", "Registered services", "bearer"},
		{"GET", "/api/v1/targets", "Deployment targets (service × cluster)", "bearer"},
		{"GET", "/api/v1/policies", "Policy Engine guardrails per cluster", "bearer"},
	}},
	{"Reliability", []endpoint{
		{"GET", "/api/v1/slos", "SLOs with error budget and burn rate", "bearer"},
		{"GET", "/api/v1/experiment-runs", "Chaos experiment runs", "bearer"},
	}},
	{"Control Plane", []endpoint{
		{"GET", "/api/v1/control-plane/scaling-events", "Auto-Scaling decisions", "bearer"},
		{"GET", "/api/v1/control-plane/healing-events", "Self-Healing actions", "bearer"},
	}},
	{"Alerts", []endpoint{
		{"GET", "/api/v1/alerts", "Alert feed", "bearer"},
		{"POST", "/api/v1/alerts/{id}/acknowledge", "Acknowledge an alert", "operator"},
		{"POST", "/api/v1/alerts/{id}/resolve", "Resolve an alert", "operator"},
	}},
}

var indexTmpl = template.Must(template.New("index").Parse(`<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>AegisCloud API</title>
<style>
:root{--bg:#0b0e14;--surface:#121722;--surface2:#1a2130;--border:#232b3b;
--text:#8b96ab;--strong:#e6ebf4;--dim:#5d6879;--accent:#4f8cff;--good:#2ecc8f;
--warn:#f5a524;--mono:ui-monospace,"Cascadia Code",Consolas,monospace}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--text);
font:15px/1.5 ui-sans-serif,system-ui,"Segoe UI",Roboto,sans-serif;
-webkit-font-smoothing:antialiased}
.wrap{max-width:860px;margin:0 auto;padding:40px 24px 64px}
.brand{display:flex;align-items:center;gap:11px;margin-bottom:6px}
.brand h1{margin:0;font-size:21px;color:var(--strong);font-weight:650;letter-spacing:-.3px}
.sub{font-size:13.5px;margin:0 0 22px}
.status{display:inline-flex;align-items:center;gap:7px;background:rgba(46,204,143,.12);
color:var(--good);border-radius:20px;padding:4px 11px;font-size:12px;font-weight:600;
margin-bottom:26px}
.dot{width:6px;height:6px;border-radius:50%;background:currentColor}
.note{background:rgba(245,165,36,.1);border:1px solid rgba(245,165,36,.25);
border-radius:8px;padding:12px 15px;font-size:13px;color:#d9a04a;line-height:1.6;
margin-bottom:26px}
h2{font-size:12px;text-transform:uppercase;letter-spacing:.7px;color:var(--dim);
font-weight:600;margin:26px 0 10px}
table{width:100%;border-collapse:collapse;background:var(--surface);
border:1px solid var(--border);border-radius:9px;overflow:hidden}
td{padding:10px 13px;border-bottom:1px solid var(--border);font-size:13px;
vertical-align:middle}
tr:last-child td{border-bottom:none}
tr:hover td{background:var(--surface2)}
.m{font-family:var(--mono);font-size:11px;font-weight:700;padding:2px 7px;
border-radius:4px;display:inline-block;min-width:44px;text-align:center}
.get{background:rgba(79,140,255,.15);color:var(--accent)}
.post{background:rgba(46,204,143,.15);color:var(--good)}
.p{font-family:var(--mono);font-size:12.5px;color:var(--strong)}
.p a{color:var(--strong);text-decoration:none;border-bottom:1px dotted var(--dim)}
.p a:hover{color:var(--accent);border-color:var(--accent)}
.d{color:var(--text);font-size:12.5px}
.a{font-size:10.5px;color:var(--dim);font-family:var(--mono);text-align:right;
white-space:nowrap}
.foot{margin-top:32px;padding-top:20px;border-top:1px solid var(--border);
font-size:12.5px;color:var(--dim);line-height:1.75}
.foot code{font-family:var(--mono);color:var(--text);background:var(--surface);
padding:1.5px 5px;border-radius:4px}
.foot a{color:var(--accent)}
</style>
</head>
<body>
<div class="wrap">
  <div class="brand">
    <svg width="27" height="27" viewBox="0 0 32 32" fill="none" aria-hidden="true">
      <path d="M16 2.5 4.5 7.2v9.1c0 7 4.9 11.9 11.5 13.2 6.6-1.3 11.5-6.2 11.5-13.2V7.2L16 2.5Z"
        fill="rgba(79,140,255,.12)" stroke="#4f8cff" stroke-width="1.6" stroke-linejoin="round"/>
      <path d="M11 16.2l3.4 3.4 6.6-6.8" stroke="#4f8cff" stroke-width="2.1"
        stroke-linecap="round" stroke-linejoin="round"/>
    </svg>
    <h1>AegisCloud API</h1>
  </div>
  <p class="sub">Cloud-agnostic reliability &amp; evaluation platform — backend service</p>

  <div class="status"><span class="dot"></span>RUNNING · {{.Version}}</div>

  <div class="note">
    This is the API service, not the dashboard. The operator UI runs separately at
    <a href="http://localhost:5173" style="color:inherit">localhost:5173</a>.
    Endpoints marked <code>bearer</code> return <strong>401</strong> in a plain browser —
    that is correct, they need a JWT.
  </div>

  {{range .Groups}}
  <h2>{{.Name}}</h2>
  <table>
    {{range .Endpoints}}
    <tr>
      <td style="width:60px"><span class="m {{if eq .Method "GET"}}get{{else}}post{{end}}">{{.Method}}</span></td>
      <td class="p">{{if and (eq .Method "GET") (eq .Auth "none")}}<a href="{{.Path}}">{{.Path}}</a>{{else}}{{.Path}}{{end}}</td>
      <td class="d">{{.Desc}}</td>
      <td class="a">{{.Auth}}</td>
    </tr>
    {{end}}
  </table>
  {{end}}

  <div class="foot">
    <strong style="color:var(--text)">Getting a token</strong><br>
    <code>curl -X POST localhost:8080/api/v1/auth/login -H "Content-Type: application/json" -d '{"email":"admin@aegiscloud.local","password":"changeme123"}'</code><br>
    Then send it as <code>Authorization: Bearer &lt;token&gt;</code>.
  </div>
</div>
</body>
</html>`))

// IndexHandler renders a browsable index of the API surface so hitting the
// service root in a browser shows something useful instead of a 404.
func IndexHandler(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	indexTmpl.Execute(w, map[string]any{
		"Version": Version,
		"Groups":  endpointGroups,
	})
}
