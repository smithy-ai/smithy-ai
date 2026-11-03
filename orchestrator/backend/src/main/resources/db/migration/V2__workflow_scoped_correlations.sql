-- A pull request, issue or branch is held by one run per workflow, not one run
-- in all: the author's run and the reviewer's run both answer to the same merge
-- request. With (kind, ref) as the key, the last workflow to correlate took the
-- handle from the one that had it. SQLite cannot change a primary key in place,
-- so the table is rebuilt around it.

CREATE TABLE run_correlations_v2 (
    kind       TEXT NOT NULL,
    ref        TEXT NOT NULL,
    run_id     TEXT NOT NULL REFERENCES runs (id) ON DELETE CASCADE,
    created_at TEXT NOT NULL,
    PRIMARY KEY (kind, ref, run_id)
);

INSERT INTO run_correlations_v2 (kind, ref, run_id, created_at)
    SELECT kind, ref, run_id, created_at FROM run_correlations;

DROP INDEX IF EXISTS idx_run_correlations_run;
DROP TABLE run_correlations;
ALTER TABLE run_correlations_v2 RENAME TO run_correlations;

CREATE INDEX idx_run_correlations_run ON run_correlations (run_id);
CREATE INDEX idx_run_correlations_ref ON run_correlations (kind, ref);
