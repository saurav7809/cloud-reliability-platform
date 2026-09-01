package io.aegiscloud.controlplane.onboarding;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out which microservices a repository contains.
 *
 * <p>The signal is build markers: a directory holding a {@code pom.xml}, a
 * {@code go.mod}, a {@code package.json} and so on is something that builds
 * independently, which is the closest thing in a source tree to "a service". A
 * {@code Dockerfile} alongside it means the thing is also independently
 * deployable, which is what the platform actually cares about.
 *
 * <p>This is inference, not certainty, so every result carries the marker it was
 * derived from — a wrong guess should be visible and correctable rather than
 * mysterious.
 */
@Component
public class ServiceDiscovery {

    /**
     * Directories that contain other people's code or build output. Scanning them
     * finds hundreds of {@code package.json} files and no services at all.
     */
    private static final Set<String> IGNORED_SEGMENTS = Set.of(
            "node_modules", "vendor", ".git", ".venv", "venv", "target", "build",
            "dist", "out", ".gradle", "__pycache__", ".terraform", "testdata");

    /** Build markers, ordered so the most specific language signal wins. */
    private static final List<Marker> MARKERS = List.of(
            new Marker("pom.xml", "Java", "Maven"),
            new Marker("build.gradle", "Java", "Gradle"),
            new Marker("build.gradle.kts", "Kotlin", "Gradle"),
            new Marker("go.mod", "Go", "Go modules"),
            new Marker("Cargo.toml", "Rust", "Cargo"),
            new Marker("pyproject.toml", "Python", "pyproject"),
            new Marker("requirements.txt", "Python", "pip"),
            new Marker("package.json", "JavaScript", "npm"),
            new Marker("Gemfile", "Ruby", "Bundler"),
            new Marker("composer.json", "PHP", "Composer"),
            // .NET project files are named after the project, not fixed, so this one
            // matches by extension.
            new Marker("*.csproj", "C#", ".NET"));

    /** A marker is either an exact filename or, when it starts with {@code *.}, an extension. */
    private record Marker(String filename, String language, String buildType) {
        boolean isExtension() {
            return filename.startsWith("*.");
        }

        String extension() {
            return filename.substring(1);
        }
    }

    private static boolean matchesMarker(String filename, Marker marker) {
        return marker.isExtension()
                ? filename.endsWith(marker.extension())
                : filename.equals(marker.filename());
    }

    private static Optional<Marker> matchMarker(String filename) {
        return MARKERS.stream().filter(m -> matchesMarker(filename, m)).findFirst();
    }

    /** EXPOSE may carry a port, a port/proto pair, or several ports. */
    private static final Pattern EXPOSE = Pattern.compile(
            "(?m)^\\s*EXPOSE\\s+(\\d{2,5})", Pattern.CASE_INSENSITIVE);

    /**
     * A service inferred from the tree.
     *
     * @param path      directory within the repository, "" for the repository root
     * @param evidence  which files led to this conclusion, for the operator to judge
     */
    public record DiscoveredService(
            String name,
            String path,
            String language,
            String buildType,
            String dockerfilePath,
            Integer containerPort,
            List<String> evidence) {
    }

    /**
     * Finds candidate services in a file listing.
     *
     * @param repoName used to name a service when the whole repository is one
     */
    public List<DiscoveredService> discover(List<GitHubClient.TreeEntry> entries, String repoName) {
        // directory -> the marker files found directly inside it
        Map<String, List<String>> markersByDirectory = new LinkedHashMap<>();
        Map<String, String> dockerfileByDirectory = new LinkedHashMap<>();

        for (GitHubClient.TreeEntry entry : entries) {
            if (!entry.isFile() || isIgnored(entry.path())) {
                continue;
            }

            String directory = parentOf(entry.path());
            String filename = fileNameOf(entry.path());

            if (isDockerfile(filename)) {
                dockerfileByDirectory.putIfAbsent(directory, entry.path());
            }
            if (matchMarker(filename).isPresent()) {
                markersByDirectory.computeIfAbsent(directory, d -> new ArrayList<>()).add(filename);
            }
        }

        // A directory with only a Dockerfile is still a deployable unit, so fold
        // those in even though no build marker names a language for them.
        for (String directory : dockerfileByDirectory.keySet()) {
            markersByDirectory.computeIfAbsent(directory, d -> new ArrayList<>());
        }

        List<DiscoveredService> discovered = new ArrayList<>();
        for (Map.Entry<String, List<String>> candidate : markersByDirectory.entrySet()) {
            String directory = candidate.getKey();
            List<String> markerFiles = candidate.getValue();
            String dockerfile = dockerfileByDirectory.get(directory);

            // Without a Dockerfile, a nested build marker is usually a library or a
            // module rather than something that gets deployed on its own.
            if (dockerfile == null && !directory.isEmpty()) {
                continue;
            }

            Optional<Marker> marker = MARKERS.stream()
                    .filter(m -> markerFiles.stream().anyMatch(f -> matchesMarker(f, m)))
                    .findFirst();

            List<String> evidence = new ArrayList<>(markerFiles);
            if (dockerfile != null) {
                evidence.add(fileNameOf(dockerfile));
            }
            if (evidence.isEmpty()) {
                continue;
            }

            discovered.add(new DiscoveredService(
                    serviceName(directory, repoName),
                    directory,
                    marker.map(Marker::language).orElse("Unknown"),
                    marker.map(Marker::buildType).orElse(dockerfile != null ? "Docker" : "Unknown"),
                    dockerfile,
                    null,
                    evidence));
        }

        discovered.sort(Comparator.comparing(DiscoveredService::path));
        return discovered;
    }

    /** Reads the first EXPOSE port out of a Dockerfile, if it declares one. */
    public Optional<Integer> containerPortFrom(String dockerfileContent) {
        if (dockerfileContent == null) {
            return Optional.empty();
        }
        Matcher matcher = EXPOSE.matcher(dockerfileContent);
        if (!matcher.find()) {
            return Optional.empty();
        }
        try {
            int port = Integer.parseInt(matcher.group(1));
            return (port > 0 && port <= 65535) ? Optional.of(port) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static boolean isDockerfile(String filename) {
        return filename.equals("Dockerfile") || filename.startsWith("Dockerfile.");
    }

    private static boolean isIgnored(String path) {
        for (String segment : path.split("/")) {
            if (IGNORED_SEGMENTS.contains(segment)) {
                return true;
            }
        }
        return false;
    }

    private static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static String fileNameOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /**
     * Directory names that describe layout rather than identity. A Dockerfile in
     * {@code src/cartservice/src} belongs to cartservice, not to something called
     * "src".
     */
    private static final Set<String> LAYOUT_DIRECTORY_NAMES = Set.of(
            "src", "app", "server", "cmd", "lib", "main", "source", "code", "docker");

    /**
     * Names a service after the deepest directory that identifies it.
     *
     * <p>Walks up past purely structural directories, because projects commonly nest
     * the buildable part one level down and the enclosing directory is the one
     * carrying the service's actual name.
     */
    private static String serviceName(String directory, String repoName) {
        if (directory.isEmpty()) {
            return repoName;
        }
        String[] segments = directory.split("/");
        for (int i = segments.length - 1; i >= 0; i--) {
            String segment = segments[i];
            if (!segment.isBlank() && !LAYOUT_DIRECTORY_NAMES.contains(segment.toLowerCase())) {
                return segment;
            }
        }
        return repoName;
    }
}
