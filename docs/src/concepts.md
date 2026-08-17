# Concepts

The model Smithy-AI works in.

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
that has a **name** (`issue.assigned`, `pr.review_submitted`, `ci.failed`) and a
small set of fields.

An adapter reports what happened; deciding what it means is the workflow's job.
The full list is in the [reference](workflows/reference.md#events).

Every event carries the **connector** it arrived through as `event.source.id` and
its implementation as `event.source.provider`. Two systems can produce the same event name: a Jira story and a
GitLab issue are both `issue.assigned`. The connector is what tells them apart.

## Connectors

A connector is one named system Smithy-AI talks to, such as `forgejo-main` or
`jira-product`. Its provider is `forgejo`, `gitlab`, `github`, or `jira`.
Which connector an event came from decides which system an action works
against, unless a step says otherwise.

In a split setup, with stories in Jira and the work in GitLab repositories, an
action answering an issue answers it where that issue lives.

## Actors

An actor is a machine identity Smithy-AI acts as, typically `smithy` for the
agent doing the work and `coordinator` for the one planning features. Each has
its own account and token on a connector.

Which actor an issue is assigned to says what kind of work it is: assigning to
the coordinator means "plan this across repositories", assigning to smithy means
"do this here". A workflow filters on it so that two workflows do not both claim
the same issue.

A workflow also declares which actor it acts as, so a plan written by the
coordinator is signed by the coordinator rather than by the agent that will
implement it.

## Workflows

A workflow definition is YAML with three parts:

**`routing`** says which events belong to this workflow, and which run each
belongs to. A rule matches on event name, optionally filtered by a `when:`
predicate, and resolves the run either from a `key:` template or from a
correlation the run registered earlier.

**`state`** is a state machine. Each state names the events it handles, the steps
to run, and where to go next.

**`vars`** are the workflow's own constants: branch prefixes, tool lists, review
lenses, a repository catalog. Readable in every expression as `vars.x`.

Definitions come from three places, later ones winning by name: built into the
jar, an operator's `/config/workflows` directory, and a repository's own
`.smithy/workflows/`.

## Runs

A run is one execution of a workflow: durable, with an id, a state, variables, a
history, and zero or more containers it holds.

A run outlives the containers it uses. Delete a container and the run is still
there, with its history.

A run is found again by **correlation**: `(kind, ref) → run`. When a workflow
opens a pull request it records `pr → this run`, so a later comment on that pull
request finds it without anyone parsing a branch name.

Runs form a tree. A coordinator spawns child runs, and when a child reaches a
terminal state the engine tells its parent.

## Steps and actions

A transition is a list of steps. Each step names an **action** and passes it a
`with:` block. An action is one typed side effect: create an issue, run an agent
turn, push a branch.

An action declares what a provider has to support for it to work. A definition
asking for something the configured provider cannot do is rejected at startup
rather than failing halfway through a run.

Transitions are resumable. Every step records its outcome, so re-running a
transition skips the steps that already finished and reuses their output. An
agent turn can run for half an hour, so a restart in the middle of one is
ordinary.

## Waiting

Two kinds. A waiting run occupies nothing while it waits, and both kinds survive
a restart:

- **A gate** waits for a human: an approval label, or a button in the dashboard.
  It is a row in the store, so it can be released days later.
- **A join** waits for other runs. `run.await` and `run.wave` report which
  children have finished and which are now free to start.

---

Next: **[writing a workflow](workflows/index.md)**, or the full
**[reference](workflows/reference.md)**.
