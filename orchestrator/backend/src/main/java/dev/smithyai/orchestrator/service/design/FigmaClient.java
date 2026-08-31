package dev.smithyai.orchestrator.service.design;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Figma's REST API, used for one thing: turning a link into a picture.
 *
 * <p>Rendering happens here rather than in the container, because the container
 * has no route to figma.com and no business holding the token. What crosses
 * into it is a PNG.
 *
 * <p>Inactive when the deployment configured no token, in which case every call
 * is refused rather than attempted — a deployment that never set up Figma
 * should see nothing happen, not a stream of 403s in its logs.
 */
@Slf4j
public class FigmaClient {

    private static final String API = "https://api.figma.com/v1";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final String token;
    private final String format;
    private final double scale;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public FigmaClient(String token, String format, double scale) {
        this.token = token == null ? "" : token.strip();
        this.format = format;
        this.scale = scale;
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    /** A client for a deployment that configured no Figma access. */
    public static FigmaClient inactive() {
        return new FigmaClient("", "png", 1);
    }

    public boolean isActive() {
        return !token.isEmpty();
    }

    public String format() {
        return format;
    }

    /**
     * What a file and the requested frames inside it are called.
     *
     * <p>Names are what make a rendered file recognisable — "checkout-modal.png"
     * says what a prompt needs to say, "1-23.png" says nothing.
     */
    public record Names(String file, Map<String, String> nodes) {
        public String nodeOr(String nodeId, String fallback) {
            String name = nodes.get(nodeId);
            return name == null || name.isBlank() ? fallback : name;
        }
    }

    public Names names(String fileKey, List<String> nodeIds) {
        if (nodeIds.isEmpty()) {
            return new Names(get("/files/%s?depth=1".formatted(encode(fileKey))).path("name").asText(""), Map.of());
        }
        var node = get("/files/%s/nodes?ids=%s&depth=1".formatted(encode(fileKey), encode(String.join(",", nodeIds))));
        var names = new LinkedHashMap<String, String>();
        var nodes = node.path("nodes");
        nodes
            .fieldNames()
            .forEachRemaining(id -> names.put(id, nodes.path(id).path("document").path("name").asText("")));
        return new Names(node.path("name").asText(""), names);
    }

    /**
     * The top-level frames of a file's first page, for a link that named no
     * frame of its own.
     *
     * <p>A whole-file link is how someone shares "the designs for this ticket".
     * Rendering the page's frames is the closest thing to what they meant; the
     * limit is there because a mature file has hundreds.
     */
    public List<String> topLevelFrames(String fileKey, int limit) {
        var file = get("/files/%s?depth=2".formatted(encode(fileKey)));
        var frames = new ArrayList<String>();
        for (var page : file.path("document").path("children")) {
            for (var child : page.path("children")) {
                if (frames.size() >= limit) return List.copyOf(frames);
                frames.add(child.path("id").asText(""));
            }
            if (!frames.isEmpty()) break; // the first page that has anything on it
        }
        frames.removeIf(String::isEmpty);
        return List.copyOf(frames);
    }

    /**
     * Temporary URLs for the rendered nodes, keyed by node id.
     *
     * <p>Figma renders asynchronously and answers with S3 links that expire, so
     * these are downloaded immediately rather than stored. A node it could not
     * render is simply absent from the map.
     */
    public Map<String, String> renderUrls(String fileKey, List<String> nodeIds) {
        if (nodeIds.isEmpty()) return Map.of();
        var response = get(
            "/images/%s?ids=%s&format=%s&scale=%s".formatted(
                encode(fileKey),
                encode(String.join(",", nodeIds)),
                encode(format),
                scaleParam()
            )
        );
        String error = response.path("err").asText("");
        if (!error.isEmpty() && !"null".equals(error)) {
            throw new IllegalStateException("Figma render failed for " + fileKey + ": " + error);
        }
        var urls = new LinkedHashMap<String, String>();
        var images = response.path("images");
        images
            .fieldNames()
            .forEachRemaining(id -> {
                String url = images.path(id).asText("");
                if (!url.isEmpty()) urls.put(id, url);
            });
        return urls;
    }

    /** A rendered image, fetched from the expiring URL Figma handed back. */
    public byte[] download(String url) {
        try {
            var request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(TIMEOUT).GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("Figma image download failed: " + response.statusCode());
            }
            return response.body();
        } catch (IOException e) {
            throw new IllegalStateException("Figma image download failed: " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Figma image download interrupted: " + url, e);
        }
    }

    /** Figma takes the scale as a number; "2" reads better in a log than "2.0". */
    private String scaleParam() {
        return scale == Math.rint(scale) ? String.valueOf((int) scale) : String.valueOf(scale);
    }

    private JsonNode get(String path) {
        if (!isActive()) throw new IllegalStateException("Figma access is not configured");
        try {
            var request = HttpRequest.newBuilder()
                .uri(URI.create(API + path))
                .header("X-Figma-Token", token)
                .timeout(TIMEOUT)
                .GET()
                .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException(
                    "Figma API error %d on GET %s: %s".formatted(response.statusCode(), path, response.body())
                );
            }
            return mapper.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("Figma API request failed: GET " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Figma API request interrupted: GET " + path, e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
