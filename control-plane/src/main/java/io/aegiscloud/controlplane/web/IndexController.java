package io.aegiscloud.controlplane.web;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The browsable index at the service root.
 *
 * <p>Ported from the Go backend's index page, which the Spring Boot port had left
 * behind: opening the API in a browser answered with a 500 from the catch-all
 * handler, which is a poor first impression of a platform whose subject is
 * reliability. Anyone who reaches this port with a browser gets the route table and
 * a link to Swagger instead.
 *
 * <p>Public on purpose. It lists paths, not data — the same information the OpenAPI
 * document already serves without a token.
 */
@RestController
public class IndexController {

    private record Endpoint(String method, String path, String description, String auth) {
    }

    private record Group(String name, List<Endpoint> endpoints) {
    }

    private static final List<Group> ROUTES = List.of(
            new Group("Public", List.of(
                    new Endpoint("GET", "/healthz", "Liveness and readiness, with pg and redis probes", "none"),
                    new Endpoint("GET", "/swagger", "Interactive API documentation", "none"),
                    new Endpoint("POST", "/api/v1/auth/login", "Exchange email and password for a JWT", "none"))),
            new Group("Identity", List.of(
                    new Endpoint("GET", "/api/v1/auth/me", "Current user profile and role", "bearer"))),
            new Group("Registry", List.of(
                    new Endpoint("GET", "/api/v1/overview", "Fleet rollup powering the dashboard", "bearer"),
                    new Endpoint("GET", "/api/v1/clusters", "Registered clusters (EKS / AKS / GKE / kind)", "bearer"),
                    new Endpoint("GET", "/api/v1/services", "Registered services", "bearer"),
                    new Endpoint("GET", "/api/v1/targets", "Deployment targets (service x cluster)", "bearer"),
                    new Endpoint("GET", "/api/v1/policies", "Policy Engine guardrails per cluster", "bearer"))),
            new Group("Deployment Engine (Phase 3)", List.of(
                    new Endpoint("POST", "/api/v1/clusters", "Register a cluster and probe it", "operator"),
                    new Endpoint("POST", "/api/v1/clusters/{id}/probe", "Re-probe a registered cluster", "operator"),
                    new Endpoint("POST", "/api/v1/deployments", "Roll a workload out to a cluster", "operator"),
                    new Endpoint("GET", "/api/v1/deployments/{cluster}/{ns}/{workload}", "Live workload status", "bearer"))),
            new Group("Onboarding (Phase 3)", List.of(
                    new Endpoint("POST", "/api/v1/projects", "Create a project", "operator"),
                    new Endpoint("POST", "/api/v1/applications/{id}/repository", "Connect a Git repository", "operator"),
                    new Endpoint("POST", "/api/v1/applications/{id}/discover", "Discover microservices in the repo", "operator"),
                    new Endpoint("PUT", "/api/v1/services/{id}/resources", "Set CPU and memory requests", "operator"))),
            new Group("Control Plane (Phase 4)", List.of(
                    new Endpoint("GET", "/api/v1/control-plane/stream", "Live decision feed (Server-Sent Events)", "bearer"),
                    new Endpoint("POST", "/api/v1/control-plane/reconcile", "Run one reconciliation cycle now", "operator"),
                    new Endpoint("GET", "/api/v1/control-plane/actions", "Action ledger: observed, concluded, done, outcome", "bearer"),
                    new Endpoint("GET", "/api/v1/control-plane/autonomy", "Autonomy level per cluster and action type", "bearer"),
                    new Endpoint("PUT", "/api/v1/control-plane/autonomy", "Change an autonomy level", "admin"),
                    new Endpoint("GET", "/api/v1/control-plane/policies/{clusterId}", "Guardrails in force for a cluster", "bearer"),
                    new Endpoint("PUT", "/api/v1/control-plane/policies/{clusterId}", "Change a cluster's guardrails", "admin"),
                    new Endpoint("GET", "/api/v1/control-plane/scaling-events", "Auto-Scaling decisions", "bearer"),
                    new Endpoint("GET", "/api/v1/control-plane/healing-events", "Self-Healing actions", "bearer"))),
            new Group("Reliability", List.of(
                    new Endpoint("GET", "/api/v1/slos", "SLOs with error budget and burn rate", "bearer"),
                    new Endpoint("GET", "/api/v1/experiment-runs", "Chaos experiment runs", "bearer"))),
            new Group("Alerts", List.of(
                    new Endpoint("GET", "/api/v1/alerts", "Alert feed", "bearer"),
                    new Endpoint("POST", "/api/v1/alerts/{id}/acknowledge", "Acknowledge an alert", "operator"),
                    new Endpoint("POST", "/api/v1/alerts/{id}/resolve", "Resolve an alert", "operator"))));

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public String index() {
        StringBuilder html = new StringBuilder(8192);
        html.append("""
                <!doctype html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>AegisCloud Control Plane</title>
                <style>
                :root{--bg:#0b0e14;--surface:#121722;--border:#232b3b;--text:#8b96ab;
                --strong:#e6ebf4;--dim:#5d6879;--accent:#4f8cff;--good:#2ecc8f;--warn:#f5a524;
                --mono:ui-monospace,"Cascadia Code",Consolas,monospace}
                *{box-sizing:border-box}
                body{margin:0;background:var(--bg);color:var(--text);
                font:14px/1.6 system-ui,-apple-system,Segoe UI,sans-serif;padding:40px 24px}
                main{max-width:940px;margin:0 auto}
                h1{color:var(--strong);font-size:22px;margin:0 0 4px}
                p.lede{margin:0 0 28px;color:var(--dim)}
                h2{color:var(--strong);font-size:14px;text-transform:uppercase;
                letter-spacing:.08em;margin:28px 0 10px}
                table{width:100%;border-collapse:collapse;background:var(--surface);
                border:1px solid var(--border);border-radius:8px;overflow:hidden}
                td{padding:8px 12px;border-top:1px solid var(--border);vertical-align:top}
                tr:first-child td{border-top:none}
                .m{font-family:var(--mono);color:var(--good);width:56px}
                .p{font-family:var(--mono);color:var(--strong)}
                .a{color:var(--dim);text-align:right;font-size:12px}
                a{color:var(--accent)}
                </style>
                </head>
                <body><main>
                <h1>AegisCloud Control Plane</h1>
                <p class="lede">Autonomous multi-cloud reliability platform.
                <a href="/swagger">Swagger UI</a> &middot;
                <a href="/healthz">Health</a></p>
                """);

        for (Group group : ROUTES) {
            html.append("<h2>").append(escape(group.name())).append("</h2><table>");
            for (Endpoint endpoint : group.endpoints()) {
                html.append("<tr><td class=\"m\">").append(escape(endpoint.method()))
                        .append("</td><td class=\"p\">").append(escape(endpoint.path()))
                        .append("</td><td>").append(escape(endpoint.description()))
                        .append("</td><td class=\"a\">").append(escape(endpoint.auth()))
                        .append("</td></tr>");
            }
            html.append("</table>");
        }

        return html.append("</main></body></html>").toString();
    }

    /** The route table is a constant, but escaping it keeps that true if it ever stops being. */
    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
