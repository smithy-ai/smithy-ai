# Concepts

Six ideas. Everything else in Smithy-AI is built from them.

```
   webhook          ┌──────────┐   claims    ┌──────────┐   drives   ┌─────────┐
  ──────────▶ event │ workflow │ ──────────▶ │   run    │ ─────────▶ │ actions │
   from a           │definition│             │ (durable)│            │(effects)│
   connector        └──────────┘             └──────────┘            └─────────┘
                     what should              what is                what it
                     happen                   happening              actually did
```

## Events

Something happened somewhere: an issue was assigned, a comment was posted, a
pipeline failed. A provider sends a webhook; an adapter turns it into an event
with a **name** — `issue.assigned`, `pr.review_submitted`, `ci.failed` — and a
small set of fields.

Adapters emit facts and nothing else. They do not decide what an event means;
that is the workflow's job. The full list is in the
[reference](workflows/reference.md#events).

Every event carries the **connector** it arrived through, readable as
`event.source`. Two systems can produce the same event name — a Jira story and a
GitLab issue are both `issue.assigned` — and this is what tells them apart.

## Connectors

A connector is one system Smithy-AI talks to: `forgejo`, `gitlab`, `github`,
`jira`. Which one an event came from decides which system an action works
against, unless a step says otherwise.

This is what allows a split setup: stories tracked in Jira, work done in GitLab
repositories. An action answering an issue answers it where the issue lives.

## Actors

An actor is a machine identity Smithy-AI acts as — typically `smithy` for the
agent doing the work and `coordinator` for the one planning features. Each has
its own account and token on a connector.

Actors matter for two reasons:

- **Which actor an issue is assigned to says what kind of work it is.** Assigning
  to the coordinator means "plan this across repositories"; assigning to smithy
  means "do this here". Without that distinction both workflows claim the same
  issue and two agents start on it.
- **Attribution.** A workflow declares which actor it acts as, so a plan written
  by the coordinator is signed by the coordinator, not by the agent that will
  implement it.

## Workflows

A workflow definition is YAML with three parts:

**`routing`** — which events belong to this workflow, and which run each belongs
to. A rule matches on event name, optionally filtered by a `when:` predicate, and
resolves the run either from a `key:` template or from a correlation the run
registered earlier.

**`state`** — a state machine. Each state names the events it handles, the steps
to run, and where to go next.

**`vars`** — the workflow's own constants. Branch prefixes, tool lists, review
lenses, a repository catalog. Readable in every expression as `vars.x`.

Definitions come from three places, later ones winning by name: built into the
jar, an operator's `/config/workflows` directory, and a repository's own
`.smithy/workflows/`.

## Runs

A run is one execution of a workflow: durable, with an id, a state, variables, a
history, and zero or more containers it holds.

**The run is the thing that exists, not the container.** Delete the container and
the run is still there with everything that happened. That is what makes history,
restart-safety and cross-workflow coordination possible at all.

A run is found again by **correlation**: `(kind, ref) → run`. When a workflow
opens a pull request it records `pr → this run`, so a later comment on that pull
request finds it without anyone parsing a branch name.

Runs form a tree. A coordinator spawns child runs, and when a child reaches a
terminal state the engine tells its parent.

## Steps and actions

A transition is a list of steps. Each step names an **action** — one typed side
effect: create an issue, run an agent turn, push a branch — and passes it a
`with:` block.

Actions are the extension point. Adding a capability means adding an action, not
editing the engine. Each declares what a provider must support, and a definition
that needs something the configured provider cannot do is rejected at startup
rather than failing halfway through someone's pull request.

Transitions are **resumable**. An agent turn can run for half an hour, so a
restart mid-transition is normal: every step records its outcome, and re-running
a transition skips what already completed and reuses its output. That is what
stops a resume opening a second pull request.

## Waiting

Two kinds, both durable, neither holding a thread:

- **A gate** waits for a human — an approval label, a dashboard button. It is a
  row in the store, so it survives restarts and can be released days later.
- **A join** waits for other runs. `run.await` and `run.wave` report which
  children have finished and which are now free to start.

---

Next: **[writing a workflow](workflows/index.md)**, or the full
**[reference](workflows/reference.md)**.
