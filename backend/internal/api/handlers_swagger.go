package api

import (
	_ "embed"
	"net/http"
)

// The spec is embedded into the binary so the container has no runtime file
// dependency and /openapi.yaml can never drift from the build it ships with.
//
//go:embed openapi.yaml
var openAPISpec []byte

// OpenAPISpecHandler serves the raw OpenAPI 3.0 document. Point Postman,
// Insomnia, or a client generator straight at this URL.
func OpenAPISpecHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/yaml; charset=utf-8")
	w.Write(openAPISpec)
}

// swaggerUI loads Swagger UI from a CDN. That keeps the Go binary small and the
// repo free of vendored JS, at the cost of needing network access the first time
// the page is opened; the spec itself is served locally either way.
const swaggerUI = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>AegisCloud API — Swagger UI</title>
<link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@5.17.14/swagger-ui.css">
<style>
body{margin:0;background:#0b0e14}
.topbar{display:none}
.swagger-ui .info{margin:26px 0}
#fallback{display:none;color:#8b96ab;font:15px/1.6 ui-sans-serif,system-ui,sans-serif;
padding:48px 24px;max-width:620px;margin:0 auto}
#fallback h1{color:#e6ebf4;font-size:19px}
#fallback code{font-family:ui-monospace,Consolas,monospace;background:#121722;
color:#8b96ab;padding:2px 6px;border-radius:4px}
#fallback a{color:#4f8cff}
</style>
</head>
<body>
<div id="swagger-ui"></div>
<div id="fallback">
  <h1>Swagger UI could not load</h1>
  <p>The interactive viewer is fetched from a CDN and the browser could not reach it —
     usually no internet access, or a blocked request.</p>
  <p>The specification itself is served locally and is unaffected:
     <a href="/openapi.yaml">/openapi.yaml</a>. Import that URL into Postman or Insomnia,
     or view it with <code>curl localhost:8080/openapi.yaml</code>.</p>
</div>
<script src="https://unpkg.com/swagger-ui-dist@5.17.14/swagger-ui-bundle.js"
        onerror="document.getElementById('fallback').style.display='block'"></script>
<script>
window.onload = function () {
  if (!window.SwaggerUIBundle) {
    document.getElementById('fallback').style.display = 'block';
    return;
  }
  SwaggerUIBundle({
    url: '/openapi.yaml',
    dom_id: '#swagger-ui',
    deepLinking: true,
    persistAuthorization: true,
    displayRequestDuration: true,
    tryItOutEnabled: true,
    syntaxHighlight: { theme: 'obsidian' }
  });
};
</script>
</body>
</html>`

func SwaggerUIHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Write([]byte(swaggerUI))
}
