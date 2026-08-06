package dev.smithyai.orchestrator.service.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Append-only outcome log (one JSON object per line) for the workflow
 * pipeline: plans posted, issues fanned out, MRs opened, CI failures,
 * review rounds, merges, turn failures. The point is measurement — the
 * numbers that tell whether a prompt or architecture change actually
 * improved outcomes before more complexity is added.
 */
@Slf4j
@Component
public class MetricsRecorder {

    private final Path path;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile boolean writable = true;

    public MetricsRecorder(Environment env) {
        this.path = Path.of(env.getProperty("METRICS_PATH", "/config/metrics.jsonl"));
    }

    /** Best-effort: metrics must never break a workflow. */
    public synchronized void record(String event, String project, String ref, Map<String, Object> extra) {
        if (!writable) return;
        try {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("ts", Instant.now().toString());
            entry.put("event", event);
            if (project != null) entry.put("project", project);
            if (ref != null) entry.put("ref", ref);
            if (extra != null) entry.putAll(extra);
            Files.writeString(
                path,
                mapper.writeValueAsString(entry) + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            log.warn("Metrics disabled: cannot write {}", path, e);
            writable = false;
        }
    }

    public void record(String event, String project, String ref) {
        record(event, project, ref, null);
    }

    /** Event counts plus a few derived rates, for the dashboard. */
    public Map<String, Object> summarize() {
        var counts = new HashMap<String, Long>();
        if (Files.exists(path)) {
            try (var lines = Files.lines(path, StandardCharsets.UTF_8)) {
                lines.forEach(line -> {
                    try {
                        var node = mapper.readTree(line);
                        counts.merge(node.path("event").asText("unknown"), 1L, Long::sum);
                    } catch (Exception ignored) {
                        // skip malformed lines
                    }
                });
            } catch (IOException e) {
                log.warn("Failed to read metrics from {}", path, e);
            }
        }
        long approved = counts.getOrDefault("child_plan_approved", 0L);
        long changeRounds = counts.getOrDefault("child_changes_requested", 0L);
        var result = new LinkedHashMap<String, Object>();
        result.put("counts", counts);
        result.put(
            "avgPlanReviewRounds",
            approved > 0 ? Math.round((1.0 + (double) changeRounds / approved) * 100.0) / 100.0 : 0
        );
        return result;
    }
}
