# Session fixtures

Transcripts used to develop the dashboard's Session tab without a running
orchestrator. `*.jsonl` here is gitignored — these are real agent sessions and
are nobody's business but yours.

Drop one in as `session.jsonl` and `npm run dev` picks it up:

```bash
cp ~/.claude/projects/<project>/<session-id>.jsonl dev/fixtures/session.jsonl
npm run dev
```

The dashboard then shows a single fake instance, `fixture.session.local`,
whose session is that file. To use a transcript somewhere else instead:

```bash
SMITHY_SESSION_FIXTURE=/path/to/other.jsonl npm run dev
```

The format is Claude Code's own session log: one JSON object per line, with
`type`, `uuid`, `timestamp` and `message`. Anything the app can't parse is
skipped, so a partial or hand-written file is fine for testing edge cases.
