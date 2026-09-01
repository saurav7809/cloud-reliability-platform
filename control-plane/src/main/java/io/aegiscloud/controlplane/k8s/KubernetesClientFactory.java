package io.aegiscloud.controlplane.k8s;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Builds a Kubernetes client for a registered cluster.
 *
 * <p>This class is the entire cloud-agnostic boundary. Every engine reaches every
 * cluster through the standard Kubernetes API and nothing else — there is no AWS,
 * Azure or GCP SDK anywhere in the codebase. An EKS cluster and a local kind cluster
 * differ by the kube context named in {@code kubeconfig_ref} and by a descriptive
 * provider label, never by a branch in control flow.
 *
 * <p>Timeouts are short on purpose. A cluster that has gone away must be reported as
 * unreachable in seconds; a reliability platform that hangs waiting on a dead cluster
 * cannot tell anyone the cluster is dead.
 */
@Component
public class KubernetesClientFactory {

    private static final Logger log = LoggerFactory.getLogger(KubernetesClientFactory.class);

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int REQUEST_TIMEOUT_MS = 10_000;

    /**
     * Opens a client against the given kube context, or the ambient default when
     * {@code kubeContext} is blank.
     *
     * <p>The caller owns the returned client and must close it — these hold
     * connection pools, and one leaked per probe would exhaust the process.
     *
     * @throws KubernetesUnavailableException if no usable configuration exists
     */
    public KubernetesClient clientFor(String kubeContext) {
        try {
            Config config = (kubeContext == null || kubeContext.isBlank())
                    ? Config.autoConfigure(null)
                    : Config.autoConfigure(kubeContext);

            if (config.getMasterUrl() == null) {
                throw new KubernetesUnavailableException(
                        "no kubeconfig entry resolved for context '" + kubeContext + "'");
            }

            config.setConnectionTimeout(CONNECT_TIMEOUT_MS);
            config.setRequestTimeout(REQUEST_TIMEOUT_MS);
            // A registration probe that silently retried would hide exactly the
            // failure the operator asked about.
            config.setRequestRetryBackoffLimit(0);

            return new KubernetesClientBuilder().withConfig(config).build();

        } catch (KubernetesUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.warn("could not build kubernetes client for context '{}': {}", kubeContext, e.getMessage());
            throw new KubernetesUnavailableException(
                    "could not load kubeconfig for context '" + kubeContext + "': " + e.getMessage(), e);
        }
    }

    /** Raised when a cluster has no usable kubeconfig, as distinct from one that is merely down. */
    public static class KubernetesUnavailableException extends RuntimeException {
        public KubernetesUnavailableException(String message) {
            super(message);
        }

        public KubernetesUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
