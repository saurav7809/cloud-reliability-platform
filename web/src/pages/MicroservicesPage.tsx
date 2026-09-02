import { useCallback, useEffect, useState, type FormEvent } from "react";
import {
  ApiError,
  getBuilds,
  getDeploymentHistory,
  getMicroservices,
  getRegistry,
  registerMicroservice,
  rollbackDeployment,
  startBuild,
  type Build,
  type Cluster,
  type DeploymentRecord,
  type Microservice,
  type RegisterResult,
} from "../api/client";
import { Badge, Card, timeAgo } from "../components/ui";

const HEALTH_TONE: Record<Microservice["health"], "good" | "warn" | "bad" | "info"> = {
  HEALTHY: "good",
  DEGRADED: "warn",
  DOWN: "bad",
  // Registered, but the cluster has no such workload. Worth its own colour: it
  // means the record and reality disagree, which no other state does.
  MISSING: "bad",
};

export function MicroservicesPage({
  token,
  clusters,
}: {
  token: string;
  clusters: Cluster[];
}) {
  const [services, setServices] = useState<Microservice[]>([]);
  const [history, setHistory] = useState<DeploymentRecord[]>([]);
  const [selected, setSelected] = useState<Microservice | null>(null);
  const [result, setResult] = useState<RegisterResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [showForm, setShowForm] = useState(false);
  const [builds, setBuilds] = useState<Build[]>([]);
  const [registry, setRegistry] = useState<{ configured: boolean; url: string } | null>(null);
  const [showBuild, setShowBuild] = useState(false);
  const [buildForm, setBuildForm] = useState({
    gitUrl: "https://github.com/saurav7809/cloud-reliability-platform",
    gitRef: "main",
    contextPath: "workloads/sample-service",
    dockerfile: "Dockerfile",
    imageName: "aegiscloud/sample-service",
    tag: "",
  });

  const reachable = clusters.filter((c) => c.status !== "UNREACHABLE");

  const [form, setForm] = useState({
    name: "",
    image: "",
    cluster: "",
    namespace: "aegiscloud-live",
    replicas: 2,
    containerPort: 8080,
    probePath: "/api/work",
    ownerTeam: "",
    dependencies: "",
  });

  const refresh = useCallback(async () => {
    try {
      setServices(await getMicroservices(token));
      setHistory(await getDeploymentHistory(token));
      setBuilds(await getBuilds(token));
      setRegistry(await getRegistry(token));
      setError(null);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not load microservices");
    }
  }, [token]);

  useEffect(() => {
    refresh();
    // Registered services change state on their own — a pod dies, a rollout
    // finishes — so the list refreshes rather than freezing at page load.
    const timer = setInterval(refresh, 15000);
    return () => clearInterval(timer);
  }, [refresh]);

  useEffect(() => {
    if (reachable.length > 0 && !form.cluster) {
      setForm((f) => ({ ...f, cluster: reachable[0].name }));
    }
  }, [reachable, form.cluster]);

  async function handleRegister(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      // "catalog=http://catalog, payments=http://payments" as typed, into the map
      // the API expects. Left as free text because a dependency list is easier to
      // paste than to build one row at a time.
      const dependencies: Record<string, string> = {};
      for (const entry of form.dependencies.split(",")) {
        const [name, url] = entry.split("=").map((s) => s.trim());
        if (name && url) dependencies[name] = url;
      }

      const registered = await registerMicroservice(token, {
        name: form.name.trim(),
        image: form.image.trim(),
        cluster: form.cluster,
        namespace: form.namespace.trim(),
        replicas: Number(form.replicas),
        containerPort: Number(form.containerPort),
        probePath: form.probePath.trim(),
        ownerTeam: form.ownerTeam.trim() || undefined,
        dependencies,
      });

      setResult(registered);
      setShowForm(false);
      await refresh();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Registration failed");
    } finally {
      setBusy(false);
    }
  }

  async function handleBuild(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const started = await startBuild(token, {
        clusterName: reachable[0]?.name ?? "",
        gitUrl: buildForm.gitUrl.trim(),
        gitRef: buildForm.gitRef.trim() || "main",
        contextPath: buildForm.contextPath.trim() || ".",
        dockerfile: buildForm.dockerfile.trim() || "Dockerfile",
        imageName: buildForm.imageName.trim(),
        tag: buildForm.tag.trim() || undefined,
      });

      // The built image name is handed straight to the registration form.
      // Building an image and then retyping its tag is the sort of gap that
      // turns two steps into a typo.
      setForm((f) => ({ ...f, image: started.image }));
      setShowBuild(false);
      setShowForm(true);
      await refresh();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not start the build");
    } finally {
      setBusy(false);
    }
  }

  async function handleRollback(service: Microservice) {
    setBusy(true);
    setError(null);
    try {
      const rolled = await rollbackDeployment(
        token,
        service.cluster,
        service.namespace,
        service.name,
      );
      setResult({
        serviceId: "",
        targetId: service.targetId,
        name: service.name,
        namespace: service.namespace,
        deployed: rolled.succeeded,
        managed: true,
        measured: true,
        steps: [`rolled back to ${rolled.rolledBackTo}`],
        detail: rolled.detail,
      });
      await refresh();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Rollback failed");
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <div className="page-head">
        <h1>Microservices</h1>
        <p>
          Registering a service declares it, deploys the image, puts it under the
          control plane and starts measuring it — in one step, because a
          half-registered service is deployed and running yet invisible to scaling,
          healing and diagnosis.
        </p>
      </div>

      {error && <p className="error-msg">{error}</p>}

      {result && (
        <Card
          title={`${result.name} — ${result.detail}`}
          meta={result.measured ? "registered" : "incomplete"}
        >
          <ol className="steps">
            {result.steps.map((step, i) => (
              <li key={i}>{step}</li>
            ))}
          </ol>
        </Card>
      )}

      <Card
        title="Build an image"
        meta={registry?.configured ? registry.url : "no registry configured"}
      >
        {!registry?.configured ? (
          <p className="muted">
            No image registry is configured, so the platform cannot build. Set{" "}
            <code>AEGISCLOUD_REGISTRY_URL</code> and restart the control plane. A
            service can still be registered from an image that already exists.
          </p>
        ) : (
          <>
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Image</th>
                    <th>From</th>
                    <th>Status</th>
                    <th>When</th>
                  </tr>
                </thead>
                <tbody>
                  {builds.slice(0, 5).map((b) => (
                    <tr key={b.id}>
                      <td className="mono small">{b.image}</td>
                      <td className="small">
                        {b.gitUrl.replace("https://github.com/", "")} @ {b.contextPath}
                      </td>
                      <td>
                        <Badge
                          tone={
                            b.status === "SUCCEEDED"
                              ? "good"
                              : b.status === "FAILED"
                                ? "bad"
                                : "info"
                          }
                        >
                          {b.status}
                        </Badge>
                      </td>
                      <td className="small">{timeAgo(b.startedAt)}</td>
                    </tr>
                  ))}
                  {builds.length === 0 && (
                    <tr>
                      <td colSpan={4} className="muted">
                        No images built yet. A build runs as a Kaniko job inside the
                        cluster, so no Docker daemon is involved.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>

            <button className="btn btn-primary" onClick={() => setShowBuild(!showBuild)}>
              {showBuild ? "Cancel" : "Build from a Git repository"}
            </button>

            {showBuild && (
              <form className="register-form" onSubmit={handleBuild}>
                <label className="field">
                  <span>Repository</span>
                  <input
                    value={buildForm.gitUrl}
                    onChange={(e) => setBuildForm({ ...buildForm, gitUrl: e.target.value })}
                    required
                  />
                  <small className="field-hint">
                    Public over HTTPS. Private repositories need credentials the
                    platform does not yet manage.
                  </small>
                </label>
                <div className="field-row">
                  <label className="field">
                    <span>Branch</span>
                    <input
                      value={buildForm.gitRef}
                      onChange={(e) => setBuildForm({ ...buildForm, gitRef: e.target.value })}
                    />
                  </label>
                  <label className="field">
                    <span>Context path</span>
                    <input
                      value={buildForm.contextPath}
                      onChange={(e) =>
                        setBuildForm({ ...buildForm, contextPath: e.target.value })
                      }
                    />
                  </label>
                  <label className="field">
                    <span>Dockerfile</span>
                    <input
                      value={buildForm.dockerfile}
                      onChange={(e) =>
                        setBuildForm({ ...buildForm, dockerfile: e.target.value })
                      }
                    />
                  </label>
                </div>
                <div className="field-row">
                  <label className="field">
                    <span>Image name</span>
                    <input
                      value={buildForm.imageName}
                      onChange={(e) =>
                        setBuildForm({ ...buildForm, imageName: e.target.value })
                      }
                      required
                    />
                  </label>
                  <label className="field">
                    <span>Tag</span>
                    <input
                      value={buildForm.tag}
                      onChange={(e) => setBuildForm({ ...buildForm, tag: e.target.value })}
                      placeholder="auto"
                    />
                  </label>
                </div>
                <button className="btn btn-primary" type="submit" disabled={busy}>
                  {busy ? "Starting..." : "Build"}
                </button>
              </form>
            )}
          </>
        )}
      </Card>

      <Card
        title="Registered services"
        meta={`${services.length} managed`}
      >
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Service</th>
                <th>Where</th>
                <th>Image</th>
                <th>Replicas</th>
                <th>Depends on</th>
                <th>Health</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {services.map((s) => (
                <tr key={s.targetId}>
                  <td className="td-strong">{s.name}</td>
                  <td className="mono">
                    {s.cluster}/{s.namespace}
                  </td>
                  <td className="mono small">{s.image}</td>
                  <td className="mono">
                    {s.readyReplicas}/{s.desiredReplicas}
                  </td>
                  <td className="small">
                    {s.dependencies.length === 0
                      ? "—"
                      : s.dependencies.map((d) => d.split("=")[0]).join(", ")}
                  </td>
                  <td>
                    <Badge tone={HEALTH_TONE[s.health]}>{s.health}</Badge>
                  </td>
                  <td>
                    <button
                      className="btn btn-ghost"
                      disabled={busy}
                      onClick={() => {
                        setSelected(s);
                        getDeploymentHistory(token, s.name).then(setHistory);
                      }}
                    >
                      History
                    </button>
                    <button
                      className="btn btn-ghost"
                      disabled={busy}
                      onClick={() => handleRollback(s)}
                    >
                      Roll back
                    </button>
                  </td>
                </tr>
              ))}
              {services.length === 0 && (
                <tr>
                  <td colSpan={7} className="muted">
                    Nothing is registered yet. A workload running in the cluster but not
                    registered here is invisible to every engine.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>

        <button className="btn btn-primary" onClick={() => setShowForm(!showForm)}>
          {showForm ? "Cancel" : "Register a microservice"}
        </button>
      </Card>

      {showForm && (
        <Card title="Register a microservice">
          <form className="register-form" onSubmit={handleRegister}>
            <label className="field">
              <span>Name</span>
              <input
                value={form.name}
                onChange={(e) => setForm({ ...form, name: e.target.value })}
                placeholder="orders"
                required
              />
              <small className="field-hint">
                Also the Kubernetes workload and Service name, so other services reach
                it at <code>http://{form.name || "orders"}</code>.
              </small>
            </label>

            <label className="field">
              <span>Image</span>
              <input
                value={form.image}
                onChange={(e) => setForm({ ...form, image: e.target.value })}
                placeholder="kind-registry:5000/aegiscloud/sample-service:v2"
                required
              />
            </label>

            <div className="field-row">
              <label className="field">
                <span>Cluster</span>
                <select
                  value={form.cluster}
                  onChange={(e) => setForm({ ...form, cluster: e.target.value })}
                  required
                >
                  {reachable.map((c) => (
                    <option key={c.id} value={c.name}>
                      {c.name}
                    </option>
                  ))}
                </select>
                {reachable.length === 0 && (
                  <small className="field-hint">
                    No cluster is reachable, so nothing can be deployed.
                  </small>
                )}
              </label>

              <label className="field">
                <span>Namespace</span>
                <input
                  value={form.namespace}
                  onChange={(e) => setForm({ ...form, namespace: e.target.value })}
                />
              </label>
            </div>

            <div className="field-row">
              <label className="field">
                <span>Replicas</span>
                <input
                  type="number"
                  min={1}
                  value={form.replicas}
                  onChange={(e) => setForm({ ...form, replicas: Number(e.target.value) })}
                />
              </label>

              <label className="field">
                <span>Container port</span>
                <input
                  type="number"
                  min={1}
                  value={form.containerPort}
                  onChange={(e) =>
                    setForm({ ...form, containerPort: Number(e.target.value) })
                  }
                />
              </label>

              <label className="field">
                <span>Probe path</span>
                <input
                  value={form.probePath}
                  onChange={(e) => setForm({ ...form, probePath: e.target.value })}
                />
                <small className="field-hint">
                  The working endpoint, not a health check — it should fail when a
                  dependency fails.
                </small>
              </label>
            </div>

            <label className="field">
              <span>Dependencies</span>
              <input
                value={form.dependencies}
                onChange={(e) => setForm({ ...form, dependencies: e.target.value })}
                placeholder="catalog=http://catalog, payments=http://payments"
              />
              <small className="field-hint">
                Passed to the container as <code>DEPENDENCIES</code>. Real calls, which
                is what makes the dependency graph and blast radius mean anything.
              </small>
            </label>

            <button className="btn btn-primary" type="submit" disabled={busy || reachable.length === 0}>
              {busy ? "Registering…" : "Register"}
            </button>
          </form>
        </Card>
      )}

      <Card
        title={selected ? `Deployment history — ${selected.name}` : "Deployment history"}
        meta={`${history.length} recorded`}
      >
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Workload</th>
                <th>Image</th>
                <th>Replaced</th>
                <th>By</th>
                <th>Result</th>
                <th>When</th>
              </tr>
            </thead>
            <tbody>
              {history.map((h) => (
                <tr key={h.id}>
                  <td className="td-strong">{h.workload}</td>
                  <td className="mono small">{h.image}</td>
                  <td className="mono small">{h.previousImage ?? "—"}</td>
                  <td className="small">{h.actor}</td>
                  <td>
                    <Badge tone={h.succeeded ? "good" : "bad"}>
                      {h.succeeded ? "applied" : "failed"}
                    </Badge>
                  </td>
                  <td className="small">{timeAgo(h.deployedAt)}</td>
                </tr>
              ))}
              {history.length === 0 && (
                <tr>
                  <td colSpan={6} className="muted">
                    No deployments recorded yet.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </Card>
    </>
  );
}
