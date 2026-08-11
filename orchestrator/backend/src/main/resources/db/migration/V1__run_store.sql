-- The run store. Until now a workflow run's identity was its Docker container
-- name and its state was a JSON file inside that container, so deleting the
-- container erased the run. These tables make the run the durable thing and
-- the container just a resource it holds.

CREATE TABLE runs (
    id               TEXT PRIMARY KEY,
    workflow_name    TEXT NOT NULL,
    workflow_version TEXT,
    status           TEXT NOT NULL,
    state            TEXT NOT NULL,
    vars_json        TEXT NOT NULL DEFAULT '{}',
    parent_run_id    TEXT REFERENCES runs (id) ON DELETE SET NULL,
    root_run_id      TEXT,
    created_at       TEXT NOT NULL,
    updated_at       TEXT NOT NULL,
    terminal_at      TEXT
);

CREATE INDEX idx_runs_status ON runs (status);
CREATE INDEX idx_runs_parent ON runs (parent_run_id);
CREATE INDEX idx_runs_workflow ON runs (workflow_name);

-- The global index from an external thing to the run that owns it. This is what
-- lets a child issue, a PR or a CI event find its run without scanning
-- containers, and what replaces encoding parentage in issue text.
CREATE TABLE run_correlations (
    kind       TEXT NOT NULL,
    ref        TEXT NOT NULL,
    run_id     TEXT NOT NULL REFERENCES runs (id) ON DELETE CASCADE,
    created_at TEXT NOT NULL,
    PRIMARY KEY (kind, ref)
);

CREATE INDEX idx_run_correlations_run ON run_correlations (run_id);

-- Append-only history, and the successor to the metrics JSONL.
CREATE TABLE run_events (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id       TEXT NOT NULL REFERENCES runs (id) ON DELETE CASCADE,
    seq          INTEGER NOT NULL,
    ts           TEXT NOT NULL,
    type         TEXT NOT NULL,
    payload_json TEXT
);

CREATE UNIQUE INDEX idx_run_events_run_seq ON run_events (run_id, seq);
CREATE INDEX idx_run_events_type ON run_events (type);

-- Per-transition step outcomes. Powers steps.<id>.<field> references and lets a
-- transition resume at the first incomplete step after a restart instead of
-- re-running side effects.
CREATE TABLE run_steps (
    run_id        TEXT NOT NULL REFERENCES runs (id) ON DELETE CASCADE,
    transition_id TEXT NOT NULL,
    step_id       TEXT NOT NULL,
    status        TEXT NOT NULL,
    output_json   TEXT,
    started_at    TEXT NOT NULL,
    ended_at      TEXT,
    PRIMARY KEY (run_id, transition_id, step_id)
);

-- Execution environments a run owns. A run may hold none, one, or several.
CREATE TABLE run_environments (
    run_id     TEXT NOT NULL REFERENCES runs (id) ON DELETE CASCADE,
    kind       TEXT NOT NULL,
    name       TEXT NOT NULL,
    state_json TEXT,
    created_at TEXT NOT NULL,
    PRIMARY KEY (run_id, kind, name)
);

CREATE UNIQUE INDEX idx_run_environments_name ON run_environments (kind, name);

-- Serializes event handling per run, and carries the human-takeover lease.
CREATE TABLE run_leases (
    run_id     TEXT PRIMARY KEY REFERENCES runs (id) ON DELETE CASCADE,
    holder     TEXT NOT NULL,
    expires_at TEXT NOT NULL
);

-- Pending joins and approval gates.
CREATE TABLE run_waits (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id       TEXT NOT NULL REFERENCES runs (id) ON DELETE CASCADE,
    kind         TEXT NOT NULL,
    wait_key     TEXT NOT NULL,
    satisfied_at TEXT,
    created_at   TEXT NOT NULL
);

CREATE INDEX idx_run_waits_run ON run_waits (run_id);
CREATE INDEX idx_run_waits_pending ON run_waits (kind, wait_key) WHERE satisfied_at IS NULL;
