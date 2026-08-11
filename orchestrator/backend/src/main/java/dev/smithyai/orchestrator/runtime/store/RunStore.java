package dev.smithyai.orchestrator.runtime.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable storage for workflow runs and everything hanging off them.
 *
 * <p>Timestamps are stored as ISO-8601 UTC text: SQLite has no timestamp type,
 * and this format sorts correctly as a string, so ordering queries work without
 * a conversion.
 */
@Slf4j
@Component
public class RunStore {

    private static final TypeReference<Map<String, Object>> VARS_TYPE = new TypeReference<>() {};

    private final JdbcClient db;
    private final ObjectMapper mapper;

    public RunStore(JdbcClient db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    // ── Runs ─────────────────────────────────────────────────

    @Transactional
    public Run create(String workflowName, String workflowVersion, String initialState, String parentRunId) {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        String rootRunId = parentRunId == null ? id : findRootOf(parentRunId);

        db
            .sql(
                """
                INSERT INTO runs (id, workflow_name, workflow_version, status, state, vars_json,
                                  parent_run_id, root_run_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, '{}', ?, ?, ?, ?)
                """
            )
            .params(
                id,
                workflowName,
                workflowVersion,
                RunStatus.PENDING.value(),
                initialState,
                parentRunId,
                rootRunId,
                iso(now),
                iso(now)
            )
            .update();

        return new Run(
            id,
            workflowName,
            workflowVersion,
            RunStatus.PENDING,
            initialState,
            Map.of(),
            parentRunId,
            rootRunId,
            now,
            now,
            null
        );
    }

    private String findRootOf(String parentRunId) {
        return db
            .sql("SELECT root_run_id FROM runs WHERE id = ?")
            .param(parentRunId)
            .query(String.class)
            .optional()
            .orElse(parentRunId);
    }

    public Optional<Run> find(String runId) {
        return db.sql("SELECT * FROM runs WHERE id = ?").param(runId).query(RUN_MAPPER).optional();
    }

    /** Runs that have not reached a terminal status, oldest first. */
    public List<Run> findActive() {
        return db
            .sql("SELECT * FROM runs WHERE status NOT IN ('completed', 'failed', 'cancelled') ORDER BY created_at")
            .query(RUN_MAPPER)
            .list();
    }

    public List<Run> findChildren(String parentRunId) {
        return db
            .sql("SELECT * FROM runs WHERE parent_run_id = ? ORDER BY created_at")
            .param(parentRunId)
            .query(RUN_MAPPER)
            .list();
    }

    /** Most recent runs first — what the dashboard lists. */
    public List<Run> findRecent(int limit) {
        return db.sql("SELECT * FROM runs ORDER BY created_at DESC LIMIT ?").param(limit).query(RUN_MAPPER).list();
    }

    @Transactional
    public void updateState(String runId, String state) {
        db
            .sql("UPDATE runs SET state = ?, updated_at = ? WHERE id = ?")
            .params(state, iso(Instant.now()), runId)
            .update();
    }

    @Transactional
    public void updateStatus(String runId, RunStatus status) {
        String now = iso(Instant.now());
        String terminalAt = status.isTerminal() ? now : null;
        db
            .sql("UPDATE runs SET status = ?, updated_at = ?, terminal_at = COALESCE(?, terminal_at) WHERE id = ?")
            .params(status.value(), now, terminalAt, runId)
            .update();
    }

    /** Replaces the whole variable map; callers read-modify-write under a lease. */
    @Transactional
    public void updateVars(String runId, Map<String, Object> vars) {
        db
            .sql("UPDATE runs SET vars_json = ?, updated_at = ? WHERE id = ?")
            .params(writeJson(vars), iso(Instant.now()), runId)
            .update();
    }

    // ── Correlations ─────────────────────────────────────────

    /**
     * Point an external handle at a run. Re-pointing an existing handle is
     * allowed — a branch or PR can be taken over by a newer run.
     */
    @Transactional
    public void correlate(CorrelationKind kind, String ref, String runId) {
        db
            .sql(
                """
                INSERT INTO run_correlations (kind, ref, run_id, created_at) VALUES (?, ?, ?, ?)
                ON CONFLICT (kind, ref) DO UPDATE SET run_id = excluded.run_id, created_at = excluded.created_at
                """
            )
            .params(kind.value(), ref, runId, iso(Instant.now()))
            .update();
    }

    public Optional<Run> findByCorrelation(CorrelationKind kind, String ref) {
        return db
            .sql(
                """
                SELECT r.* FROM runs r
                JOIN run_correlations c ON c.run_id = r.id
                WHERE c.kind = ? AND c.ref = ?
                """
            )
            .params(kind.value(), ref)
            .query(RUN_MAPPER)
            .optional();
    }

    @Transactional
    public void removeCorrelation(CorrelationKind kind, String ref) {
        db.sql("DELETE FROM run_correlations WHERE kind = ? AND ref = ?").params(kind.value(), ref).update();
    }

    // ── Events ───────────────────────────────────────────────

    /**
     * Append to the run's history. The sequence number is assigned from the
     * run's current maximum, so the timeline stays ordered even when two
     * appends land in the same millisecond.
     */
    @Transactional
    public long appendEvent(String runId, String type, Map<String, Object> payload) {
        long seq = db
            .sql("SELECT COALESCE(MAX(seq), 0) + 1 FROM run_events WHERE run_id = ?")
            .param(runId)
            .query(Long.class)
            .single();
        db
            .sql("INSERT INTO run_events (run_id, seq, ts, type, payload_json) VALUES (?, ?, ?, ?, ?)")
            .params(runId, seq, iso(Instant.now()), type, payload == null ? null : writeJson(payload))
            .update();
        return seq;
    }

    public List<RunEvent> findEvents(String runId) {
        return db.sql("SELECT * FROM run_events WHERE run_id = ? ORDER BY seq").param(runId).query(EVENT_MAPPER).list();
    }

    /** Event-type counts across all runs — the dashboard's metric row. */
    public Map<String, Long> countEventsByType() {
        return db
            .sql("SELECT type, COUNT(*) AS n FROM run_events GROUP BY type")
            .query((ResultSet rs, int i) -> Map.entry(rs.getString("type"), rs.getLong("n")))
            .list()
            .stream()
            .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    // ── Steps ────────────────────────────────────────────────

    /**
     * Record that a step is starting. Returns false if it already completed —
     * the caller should skip it and reuse {@link #findStepOutput}.
     */
    @Transactional
    public boolean beginStep(String runId, String transitionId, String stepId) {
        var existing = db
            .sql("SELECT status FROM run_steps WHERE run_id = ? AND transition_id = ? AND step_id = ?")
            .params(runId, transitionId, stepId)
            .query(String.class)
            .optional();
        if (existing.filter("completed"::equals).isPresent()) return false;

        db
            .sql(
                """
                INSERT INTO run_steps (run_id, transition_id, step_id, status, started_at) VALUES (?, ?, ?, 'running', ?)
                ON CONFLICT (run_id, transition_id, step_id) DO UPDATE SET status = 'running', started_at = excluded.started_at
                """
            )
            .params(runId, transitionId, stepId, iso(Instant.now()))
            .update();
        return true;
    }

    @Transactional
    public void completeStep(String runId, String transitionId, String stepId, Map<String, Object> output) {
        db
            .sql(
                """
                UPDATE run_steps SET status = 'completed', output_json = ?, ended_at = ?
                WHERE run_id = ? AND transition_id = ? AND step_id = ?
                """
            )
            .params(output == null ? null : writeJson(output), iso(Instant.now()), runId, transitionId, stepId)
            .update();
    }

    @Transactional
    public void failStep(String runId, String transitionId, String stepId, String error) {
        db
            .sql(
                """
                UPDATE run_steps SET status = 'failed', output_json = ?, ended_at = ?
                WHERE run_id = ? AND transition_id = ? AND step_id = ?
                """
            )
            .params(writeJson(Map.of("error", String.valueOf(error))), iso(Instant.now()), runId, transitionId, stepId)
            .update();
    }

    public Optional<Map<String, Object>> findStepOutput(String runId, String transitionId, String stepId) {
        return db
            .sql(
                """
                SELECT output_json FROM run_steps
                WHERE run_id = ? AND transition_id = ? AND step_id = ? AND status = 'completed'
                """
            )
            .params(runId, transitionId, stepId)
            .query(String.class)
            .optional()
            .map(this::readVars);
    }

    /** Completed step outputs for a transition, keyed by step id. */
    public Map<String, Map<String, Object>> findStepOutputs(String runId, String transitionId) {
        var result = new java.util.LinkedHashMap<String, Map<String, Object>>();
        db
            .sql(
                """
                SELECT step_id, output_json FROM run_steps
                WHERE run_id = ? AND transition_id = ? AND status = 'completed' ORDER BY started_at
                """
            )
            .params(runId, transitionId)
            .query((ResultSet rs, int i) -> Map.entry(rs.getString("step_id"), readVars(rs.getString("output_json"))))
            .list()
            .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    // ── Environments ─────────────────────────────────────────

    /**
     * Attach an execution environment to a run. A container name is unique
     * across runs, so claiming one already held elsewhere is rejected.
     */
    @Transactional
    public void attachEnvironment(String runId, String kind, String name, Map<String, Object> state) {
        try {
            db
                .sql(
                    """
                    INSERT INTO run_environments (run_id, kind, name, state_json, created_at) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (run_id, kind, name) DO UPDATE SET state_json = excluded.state_json
                    """
                )
                .params(runId, kind, name, state == null ? null : writeJson(state), iso(Instant.now()))
                .update();
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException(
                "Environment %s/%s is already attached to another run".formatted(kind, name),
                e
            );
        }
    }

    public Optional<Run> findByEnvironment(String kind, String name) {
        return db
            .sql(
                """
                SELECT r.* FROM runs r
                JOIN run_environments e ON e.run_id = r.id
                WHERE e.kind = ? AND e.name = ?
                """
            )
            .params(kind, name)
            .query(RUN_MAPPER)
            .optional();
    }

    public List<String> findEnvironmentNames(String runId, String kind) {
        return db
            .sql("SELECT name FROM run_environments WHERE run_id = ? AND kind = ? ORDER BY created_at")
            .params(runId, kind)
            .query(String.class)
            .list();
    }

    @Transactional
    public void detachEnvironment(String kind, String name) {
        db.sql("DELETE FROM run_environments WHERE kind = ? AND name = ?").params(kind, name).update();
    }

    // ── Waits ────────────────────────────────────────────────

    /**
     * Arm a wait, or report on the one already armed.
     *
     * <p>A run blocked on something — a human approval, a sibling reaching a
     * state — records it here rather than parking a thread, because the thing it
     * waits for arrives as a webhook minutes or days later. Re-arming a key that
     * was already satisfied reports satisfied, which is what makes a replayed
     * transition walk past a gate it already cleared.
     *
     * @return whether the wait is already satisfied
     */
    @Transactional
    public boolean openWait(String runId, String kind, String waitKey) {
        var existing = findWait(runId, waitKey);
        if (existing.isPresent()) return existing.get().satisfiedAt() != null;

        db
            .sql("INSERT INTO run_waits (run_id, kind, wait_key, created_at) VALUES (?, ?, ?, ?)")
            .params(runId, kind, waitKey, iso(Instant.now()))
            .update();
        return false;
    }

    public Optional<RunWait> findWait(String runId, String waitKey) {
        return db
            .sql("SELECT * FROM run_waits WHERE run_id = ? AND wait_key = ? ORDER BY id DESC LIMIT 1")
            .params(runId, waitKey)
            .query(WAIT_MAPPER)
            .optional();
    }

    /**
     * Release a run's wait on a key, whatever armed it.
     *
     * <p>Deliberately indifferent to kind: a wait for {@code children-done} is
     * satisfied the same way whether a human approved it in the dashboard or a
     * child run signalled it.
     *
     * <p>A release that arrives before anything is waiting is recorded rather
     * than dropped, and satisfies the wait when it is armed. That window is real:
     * a coordinator arms its join in the same transition that spawns the children
     * it joins on, so a fast child can report back first. Dropping the release
     * would hang the coordinator forever.
     *
     * @return how many pending waits this released
     */
    @Transactional
    public int satisfyWait(String runId, String waitKey) {
        int released = db
            .sql("UPDATE run_waits SET satisfied_at = ? WHERE run_id = ? AND wait_key = ? AND satisfied_at IS NULL")
            .params(iso(Instant.now()), runId, waitKey)
            .update();
        if (released == 0 && findWait(runId, waitKey).isEmpty()) {
            String now = iso(Instant.now());
            db
                .sql("INSERT INTO run_waits (run_id, kind, wait_key, satisfied_at, created_at) VALUES (?, ?, ?, ?, ?)")
                .params(runId, "early", waitKey, now, now)
                .update();
        }
        return released;
    }

    /** Everything a run is currently blocked on — what the dashboard shows as "waiting". */
    public List<RunWait> findPendingWaits(String runId) {
        return db
            .sql("SELECT * FROM run_waits WHERE run_id = ? AND satisfied_at IS NULL ORDER BY id")
            .param(runId)
            .query(WAIT_MAPPER)
            .list();
    }

    // ── Helpers ──────────────────────────────────────────────

    private static String iso(Instant instant) {
        return instant.toString();
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize run JSON", e);
        }
    }

    private Map<String, Object> readVars(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return mapper.readValue(json, VARS_TYPE);
        } catch (Exception e) {
            log.warn("Unreadable vars_json, treating as empty", e);
            return Map.of();
        }
    }

    private final RowMapper<Run> RUN_MAPPER = (ResultSet rs, int rowNum) ->
        new Run(
            rs.getString("id"),
            rs.getString("workflow_name"),
            rs.getString("workflow_version"),
            RunStatus.fromValue(rs.getString("status")),
            rs.getString("state"),
            readVars(rs.getString("vars_json")),
            rs.getString("parent_run_id"),
            rs.getString("root_run_id"),
            parseInstant(rs, "created_at"),
            parseInstant(rs, "updated_at"),
            parseInstant(rs, "terminal_at")
        );

    private final RowMapper<RunEvent> EVENT_MAPPER = (ResultSet rs, int rowNum) ->
        new RunEvent(
            rs.getLong("id"),
            rs.getString("run_id"),
            rs.getLong("seq"),
            parseInstant(rs, "ts"),
            rs.getString("type"),
            readVars(rs.getString("payload_json"))
        );

    private final RowMapper<RunWait> WAIT_MAPPER = (ResultSet rs, int rowNum) ->
        new RunWait(
            rs.getLong("id"),
            rs.getString("run_id"),
            rs.getString("kind"),
            rs.getString("wait_key"),
            parseInstant(rs, "satisfied_at"),
            parseInstant(rs, "created_at")
        );

    private static Instant parseInstant(ResultSet rs, String column) throws SQLException {
        String raw = rs.getString(column);
        return raw == null ? null : Instant.parse(raw);
    }
}
