package io.aegiscloud.controlplane.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * A read-only client for the GitHub REST API.
 *
 * <p>Only the three calls onboarding needs: repository metadata, a recursive file
 * tree, and raw file contents. Nothing here writes to GitHub — the platform reads a
 * repository to learn what is in it, and has no business pushing to somebody's code.
 *
 * <p>Authentication is optional. Public repositories work without a token at 60
 * requests per hour; setting {@code AEGISCLOUD_GITHUB_TOKEN} raises that to 5,000
 * and allows private repositories. Rate-limit exhaustion is reported as such rather
 * than as a generic failure, because the two need completely different responses
 * from whoever is looking at the error.
 */
@Component
public class GitHubClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubClient.class);
    private static final String API_ROOT = "https://api.github.com";

    private final RestClient http;
    private final String token;

    public GitHubClient(@Value("${AEGISCLOUD_GITHUB_TOKEN:}") String token) {
        this.token = token == null ? "" : token.trim();
        this.http = RestClient.builder()
                .baseUrl(API_ROOT)
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .defaultHeader("User-Agent", "AegisCloud")
                .build();

        log.info("github client configured ({})",
                this.token.isEmpty() ? "unauthenticated — 60 req/hour, public repos only"
                        : "authenticated");
    }

    public boolean isAuthenticated() {
        return !token.isEmpty();
    }

    /** Repository coordinates as GitHub reports them. */
    public record RepositoryInfo(String fullName, String defaultBranch, String htmlUrl,
                                 boolean isPrivate) {
    }

    /** One entry in a repository's file tree. */
    public record TreeEntry(String path, String type, long size) {
        public boolean isFile() {
            return "blob".equals(type);
        }
    }

    public RepositoryInfo repository(String owner, String repo) {
        JsonNode body = get("/repos/{owner}/{repo}", owner, repo);
        return new RepositoryInfo(
                body.path("full_name").asText(owner + "/" + repo),
                body.path("default_branch").asText("main"),
                body.path("html_url").asText("https://github.com/" + owner + "/" + repo),
                body.path("private").asBoolean(false));
    }

    /**
     * Lists every file in the repository at the given ref, in one request.
     *
     * <p>GitHub truncates this response for very large repositories. That is
     * reported rather than hidden: a partial tree means discovery may have missed
     * services, and silently returning fewer results would look identical to a
     * repository that genuinely has fewer.
     */
    public TreeListing tree(String owner, String repo, String ref) {
        JsonNode body = get("/repos/{owner}/{repo}/git/trees/{ref}?recursive=1", owner, repo, ref);

        List<TreeEntry> entries = new ArrayList<>();
        for (JsonNode node : body.path("tree")) {
            entries.add(new TreeEntry(
                    node.path("path").asText(),
                    node.path("type").asText(),
                    node.path("size").asLong(0)));
        }
        return new TreeListing(entries, body.path("truncated").asBoolean(false));
    }

    public record TreeListing(List<TreeEntry> entries, boolean truncated) {
    }

    /** Fetches and decodes a text file, or empty if it cannot be read. */
    public Optional<String> fileContent(String owner, String repo, String ref, String path) {
        try {
            JsonNode body = get("/repos/{owner}/{repo}/contents/{path}?ref={ref}",
                    owner, repo, path, ref);

            if (!"base64".equals(body.path("encoding").asText())) {
                return Optional.empty();
            }
            String encoded = body.path("content").asText("").replaceAll("\\s", "");
            return Optional.of(new String(Base64.getDecoder().decode(encoded)));

        } catch (Exception e) {
            log.debug("could not read {}:{} — {}", repo, path, e.getMessage());
            return Optional.empty();
        }
    }

    private JsonNode get(String uriTemplate, Object... vars) {
        RestClient.RequestHeadersSpec<?> request = http.get().uri(uriTemplate, vars);
        if (isAuthenticated()) {
            request = request.header("Authorization", "Bearer " + token);
        }

        return request
                .exchange((req, response) -> {
                    HttpStatusCode status = response.getStatusCode();

                    if (status.is2xxSuccessful()) {
                        return response.bodyTo(JsonNode.class);
                    }
                    throw translate(status, response.getHeaders().getFirst("x-ratelimit-remaining"));
                });
    }

    private GitHubException translate(HttpStatusCode status, String rateLimitRemaining) {
        int code = status.value();

        if (code == 404) {
            return new GitHubException(isAuthenticated()
                    ? "repository not found, or the configured token cannot see it"
                    : "repository not found — if it is private, set AEGISCLOUD_GITHUB_TOKEN");
        }
        if (code == 401) {
            return new GitHubException("AEGISCLOUD_GITHUB_TOKEN was rejected by GitHub");
        }
        if (code == 403 && "0".equals(rateLimitRemaining)) {
            return new GitHubException(isAuthenticated()
                    ? "GitHub API rate limit exhausted; retry after it resets"
                    : "GitHub's unauthenticated rate limit (60/hour) is exhausted — "
                      + "set AEGISCLOUD_GITHUB_TOKEN to raise it to 5,000/hour");
        }
        if (code == 403) {
            return new GitHubException("GitHub refused the request (403)");
        }
        return new GitHubException("GitHub returned HTTP " + code);
    }

    /** A failure talking to GitHub, carrying a message meant for a human to act on. */
    public static class GitHubException extends RuntimeException {
        public GitHubException(String message) {
            super(message);
        }
    }
}
