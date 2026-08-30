#!/usr/bin/env bash
# Build the sample workload, load it into the kind cluster, and deploy it.
#
# kind nodes cannot pull from your local Docker daemon, so the image has to be
# side-loaded with `kind load` — without that step pods sit in ErrImagePull.
set -euo pipefail

CLUSTER="${CLUSTER:-aegiscloud-local}"
IMAGE="aegiscloud/sample-service:dev"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "==> Building $IMAGE"
docker build -t "$IMAGE" "$HERE/sample-service"

echo "==> Loading image into kind cluster '$CLUSTER'"
kind load docker-image "$IMAGE" --name "$CLUSTER"

echo "==> Ensuring namespace"
kubectl apply -f "$HERE/../infra/k8s/namespace.yaml"

echo "==> Applying workloads"
kubectl apply -f "$HERE/k8s/workloads.yaml"

echo "==> Waiting for rollout"
for d in checkout-service catalog-service auth-service; do
  kubectl rollout status "deployment/$d" -n aegiscloud --timeout=90s
done

echo
kubectl get pods -n aegiscloud -o wide
echo
echo "Done. Port-forward one to poke at it:"
echo "  kubectl port-forward -n aegiscloud svc/checkout-service 9090:80"
echo "  curl localhost:9090/metrics"
