package io.aegiscloud.controlplane;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cloud-agnostic boundary, enforced rather than asserted.
 *
 * <p>The architecture's central claim is that every cluster is reached through the
 * Kubernetes API alone, so EKS, AKS, GKE and a laptop's kind cluster differ by a
 * kubeconfig context and a label — never by a branch in control flow. That claim is
 * easy to state in a document and easy to break with one convenient import.
 *
 * <p>These tests make breaking it fail the build. They are deliberately crude: a
 * dependency check and a source scan, not an architecture-rule framework, because
 * the property being protected is simple enough that the check should be readable by
 * anyone who trips over it.
 */
class CloudAgnosticBoundaryTest {

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");

    /** Packages that would mean the platform had learned which cloud it is talking to. */
    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "com.amazonaws",
            "software.amazon.awssdk",
            "com.azure",
            "com.microsoft.azure",
            "com.google.cloud",
            "io.kubernetes.client.openapi.apis.EksApi");

    @Test
    @DisplayName("no cloud provider SDK is imported anywhere in the platform")
    void noCloudSdkImports() throws IOException {
        try (Stream<Path> sources = Files.walk(SOURCE_ROOT)) {
            List<String> offenders = sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .flatMap(path -> readLines(path).stream()
                            .filter(line -> line.startsWith("import "))
                            .filter(line -> FORBIDDEN_IMPORTS.stream().anyMatch(line::contains))
                            .map(line -> path.getFileName() + ": " + line.trim()))
                    .toList();

            assertThat(offenders)
                    .as("a cloud SDK import breaks the boundary that makes every provider "
                            + "the same code path")
                    .isEmpty();
        }
    }

    /**
     * The provider is a label, so nothing should branch on it.
     *
     * <p>Scans for control flow keyed on a specific provider — the shape that turns
     * "one code path for every cloud" into four code paths wearing one name. Reading
     * the provider to display it, group by it or record it is fine and common; that
     * is why the check looks for comparisons rather than for mentions.
     */
    @Test
    @DisplayName("no engine branches on which cloud provider a cluster belongs to")
    void noProviderSpecificBranching() throws IOException {
        List<String> providerComparisons = List.of(
                "ProviderType.AWS ==", "== Models.ProviderType.AWS",
                "ProviderType.GCP ==", "== Models.ProviderType.GCP",
                "ProviderType.AZURE ==", "== Models.ProviderType.AZURE",
                "provider.equals(\"AWS\")", "provider.equals(\"GCP\")", "provider.equals(\"AZURE\")");

        try (Stream<Path> sources = Files.walk(SOURCE_ROOT)) {
            List<String> offenders = sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .flatMap(path -> readLines(path).stream()
                            .filter(line -> providerComparisons.stream().anyMatch(line::contains))
                            .map(line -> path.getFileName() + ": " + line.trim()))
                    .toList();

            assertThat(offenders)
                    .as("the provider is a descriptive label; branching on it means the "
                            + "clouds are no longer interchangeable")
                    .isEmpty();
        }
    }

    /**
     * Every cluster is reached through the factory that owns the boundary.
     *
     * <p>If some class built its own client it could quietly point at something the
     * factory would not, and the single place that governs timeouts, retries and
     * credentials would no longer be single.
     */
    @Test
    @DisplayName("Kubernetes clients are built only by KubernetesClientFactory")
    void clientsComeFromOnePlace() throws IOException {
        try (Stream<Path> sources = Files.walk(SOURCE_ROOT)) {
            List<String> offenders = sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals("KubernetesClientFactory.java"))
                    .flatMap(path -> readLines(path).stream()
                            .filter(line -> line.contains("new KubernetesClientBuilder"))
                            .map(line -> path.getFileName() + ": " + line.trim()))
                    .toList();

            assertThat(offenders).isEmpty();
        }
    }

    private static List<String> readLines(Path path) {
        try {
            return Files.readAllLines(path);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + path, e);
        }
    }
}
