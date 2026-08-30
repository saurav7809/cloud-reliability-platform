import type { Cluster, Policy } from "../api/client";
import { Card, ProviderTag, StatusBadge, Badge } from "../components/ui";

export function ClustersPage({
  clusters,
  policies,
}: {
  clusters: Cluster[];
  policies: Policy[];
}) {
  return (
    <>
      <div className="page-head">
        <h1>Clusters</h1>
        <p>
          Every cluster is reached the same way — through the Kubernetes API using its
          kubeconfig. EKS, AKS, GKE and local kind differ only by a provider label.
        </p>
      </div>

      <Card title="Registered Clusters" meta={`${clusters.length} total`}>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Cluster</th>
                <th>Provider</th>
                <th>Distribution</th>
                <th>Region</th>
                <th>Nodes</th>
                <th>Version</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {clusters.map((c) => (
                <tr key={c.id}>
                  <td className="td-strong">
                    {c.name}
                    {c.isLocal && (
                      <span style={{ marginLeft: 8 }}>
                        <Badge tone="info">LOCAL</Badge>
                      </span>
                    )}
                  </td>
                  <td>
                    <ProviderTag provider={c.provider} />
                  </td>
                  <td>{c.distribution}</td>
                  <td className="mono">{c.region}</td>
                  <td className="mono">{c.nodeCount}</td>
                  <td className="mono">{c.k8sVersion}</td>
                  <td>
                    <StatusBadge status={c.status} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>

      <div style={{ marginTop: 14 }}>
        <Card title="Policy Engine Guardrails" meta="checked before every action">
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Cluster</th>
                  <th>Max Replicas</th>
                  <th>Max Concurrent Experiments</th>
                  <th>Protected Namespaces</th>
                </tr>
              </thead>
              <tbody>
                {policies.map((p) => (
                  <tr key={p.id}>
                    <td className="td-strong">{p.clusterName}</td>
                    <td className="mono">{p.maxReplicas}</td>
                    <td className="mono">
                      {p.maxConcurrentExperiments === 0 ? (
                        <Badge tone="bad">BLOCKED</Badge>
                      ) : (
                        p.maxConcurrentExperiments
                      )}
                    </td>
                    <td className="mono" style={{ whiteSpace: "normal" }}>
                      {p.protectedNamespaces.join(", ")}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      </div>
    </>
  );
}
