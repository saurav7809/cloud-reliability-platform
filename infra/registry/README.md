# Local image registry

Builds push here and the cluster pulls from here. Two names for one registry,
which is the part that trips people up: the host reaches it at `localhost:5001`,
while the kind node reaches it at `kind-registry:5000` over the `kind` Docker
network. The platform is configured with the second, because the address that
matters is the one the kubelet resolves.

```bash
docker run -d --restart=always -p 127.0.0.1:5001:5000 --name kind-registry registry:2
docker network connect kind kind-registry
```

containerd then has to be told the registry exists and speaks plain HTTP:

```bash
docker exec aegiscloud-local-control-plane sh -c 'cat >> /etc/containerd/config.toml <<EOF

[plugins."io.containerd.grpc.v1.cri".registry]
  config_path = "/etc/containerd/certs.d"
EOF
mkdir -p /etc/containerd/certs.d/kind-registry:5000
cat > /etc/containerd/certs.d/kind-registry:5000/hosts.toml <<EOF
server = "http://kind-registry:5000"

[host."http://kind-registry:5000"]
  capabilities = ["pull", "resolve"]
  skip_verify = true
EOF'

docker exec aegiscloud-local-control-plane systemctl restart containerd
```

`certs.d` rather than an inline mirror, because containerd deprecated the inline
form. Restarting containerd briefly disturbs running pods; they recover on their
own, and it is worth doing once rather than recreating the cluster.

Then point the control plane at it:

```bash
AEGISCLOUD_REGISTRY_URL=kind-registry:5000
```

## Building through the platform

```bash
POST /api/v1/builds
{
  "clusterName": "aegiscloud-local",
  "gitUrl": "https://github.com/saurav7809/cloud-reliability-platform",
  "gitRef": "main",
  "contextPath": "workloads/sample-service",
  "imageName": "aegiscloud/sample-service",
  "tag": "built-by-platform"
}
```

The build runs as a Kaniko Job in `aegiscloud-builds`, so no Docker daemon and
no privileged access are involved. Poll `/api/v1/builds/{id}`; on failure the
record carries the tail of the Kaniko log, which is where the reason lives.

Credentials are the honest limit: this clones public repositories over HTTPS.
Private repositories need secrets management the platform has not built, and
accepting a token in a request body would be worse than the limitation.
