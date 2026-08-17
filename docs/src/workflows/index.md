# Writing a workflow

Building a workflow from scratch. For the complete field-by-field surface, see
the [reference](reference.md).

Assumes you have read [Concepts](../concepts.md).

## The smallest thing that works

Put this in `/config/workflows/greeter.yml`:

```yaml
apiVersion: smithy.ai/v1alpha1
kind: Workflow
metadata:
  name: greeter

routing:
  - event: issue.assigned
    action: create
    key: "{{ repo.fullName }}#{{ event.issueRef }}"

state:
  initial: new
  new:
    on:
      issue.assigned:
        steps:
          - uses: issue.comment
            with:
              owner: "{{ repo.owner }}"
              repo: "{{ repo.name }}"
              issue: "{{ event.issueRef }}"
              body: "Seen it."
```

Restart the orchestrator. Assign an issue to the bot and it comments. That is a
complete workflow: routing, a state, a step.

The log tells you whether it loaded:

```
4 workflow definition(s) available: [architect-learn, architect-review, greeter, smithy-development]
```

If yours is missing, it failed to parse or asked for something this deployment
cannot do; the line above it says which.

## Routing: which run does this belong to?

Routing answers one question per event: **which run**, and **what to do with it**.

```yaml
routing:
  - event: issue.assigned
    when: "{{ event.assignee == 'smithy' }}"
    action: create
    key: "{{ repo.fullName }}#{{ event.issueRef }}"
```

- **`event`**: one name or a list.
- **`when`**: an optional predicate. Anything that does not render to `true` is
  false. This is how two workflows listening for the same event stay out of each
  other's way.
- **`action`**: `create`, `dispatch`, `destroy` or `ignore`.
- **`key`**: a template that must produce the same string for the same piece of
  work. This is the run's identity.

The first matching rule per workflow wins, so ordering in the file is arbitration
you can see.

### Finding a run without a key

A pull request event carries no issue reference. Instead a run records what it
owns, and routing reads that back:

```yaml
  # when the run opened the pull request
  - uses: correlate
    with:
      kind: pr
      ref: "{{ vars.owner }}/{{ vars.repo }}!{{ steps.pr.number }}"

  # ...and later, in routing
  - event: [pr.commented, pr.review_submitted]
    action: dispatch
    by: pr
```

`by:` takes `issue`, `pr`, `branch` or `container`.

## State: what happens, and when

```yaml
state:
  initial: new
  terminal: done

  new:
    on:
      issue.assigned:
        to: working
        steps: [...]

  working:
    on:
      issue.commented:
        steps: [...]        # no `to:`, so the run stays put
      pr.ready_for_review:
        to: done
        steps: [...]

  done:
    on: {}
```

An event a state does not name is ignored. That is normal, not an error. Reaching
`terminal` completes the run, releases its container, and tells its parent if it
has one.

## Steps

```yaml
steps:
  - uses: agent.run
    id: plan                      # so later steps can read steps.plan.*
    if: "{{ vars.enabled }}"      # optional
    with:
      template: refinement.md.j2
      tools: [Read, Glob, Grep]
```

Give a step an `id` when something later needs its output. `steps.plan.reply`
reads the agent's answer.

### Resumability

A transition can be interrupted, and a half-hour agent turn makes that ordinary.
On resume, completed steps are skipped and their recorded output reused. Actions
declare whether repeating them is harmless; the ones that are not, such as
opening a pull request or creating an issue, are never repeated.

Write steps plainly and rely on that: a step does not need to check whether it
already ran.

## Expressions

`{{ ... }}` is Jinja over a fixed set of names: `run`, `vars`, `steps`, `event`,
`repo`, plus `item` and `index` inside a `foreach`. The full context is in the
[reference](reference.md#expression-context).

Two behaviours to be aware of.

A value that is exactly one expression yields the object rather than its text, so
`items: "{{ vars.plan }}"` iterates a list. Add surrounding text and it becomes a
string.

Conditions must render to the literal `true`. This looks right and is always
false:

```yaml
if: "{{ event.approver }}"          # renders "alice", not "true"
if: "{{ event.approver != '' }}"    # correct
```

Two extra filters are available for branch and reference names: `slug` and
`displayRef`.

## Doing something per item

```yaml
- uses: foreach
  id: fanout
  with:
    items: "{{ vars.plan }}"
  steps:
    - uses: issue.create
      with:
        owner: "{{ item.owner }}"
        repo: "{{ item.repo }}"
        title: "{{ item.title }}"
```

Each iteration gets its own transition identity, so an interrupted fan-out
resumes without creating anything twice.

## Sharing steps between transitions

Name a list of steps under `actions:` and `uses:` it from more than one place:

```yaml
actions:
  fanOut:
    steps:
      - uses: foreach
        ...

state:
  awaiting_approval:
    on:
      issue.plan_approved:
        steps:
          - uses: fanOut
      "signal:gate-approved":
        steps:
          - uses: fanOut
```

The built-in coordinator uses this so that approving by label and approving from
the dashboard run the same steps.

## Waiting for a person

```yaml
- uses: gate.await
  id: approval
  with:
    key: plan-approval
```

The gate records itself and the transition ends; nothing blocks. It is released
by whatever the workflow treats as approval: a label producing
`issue.plan_approved`, or the dashboard, which emits `signal:gate-approved`.

## Waiting for other runs

```yaml
- uses: run.spawn
  with:
    workflow: smithy-development
    index: "{{ index }}"
    dependsOn: "{{ item.dependsOn }}"

- uses: run.wave
  id: wave                     # steps.wave.released lists what may start now
```

The engine signals a parent `child-done` whenever a child reaches a terminal
state, so a child workflow needs no knowledge that it is a child. See
[coordinating across repositories](coordinator.md).

## Configuring a shipped workflow

Do not copy it. Extend it and supply only variables:

```yaml
metadata:
  name: acme-coordinator
  extends: feature-coordinator
vars:
  storyRepos: [acme/product]
  catalog: [...]
```

`extends` contributes variables only; routing and states come from the base. A
workflow that extends another shadows it, so the base does not also run.

## Testing one

There is no dry-run mode yet. In practice:

1. Watch the startup line to confirm it loaded.
2. Trigger it and watch the log. It records every run a workflow claimed, every
   step that failed, and every reason a workflow decided an event was not
   its own.
3. Read the run's timeline in the dashboard: every event it received and every
   `metrics.record` it wrote, in order.

A step that fails leaves the run where it was. Fix the definition, re-send the
event, and it carries on from that step.

## Common mistakes

| Symptom | Cause |
|---|---|
| Workflow never claims anything | `when:` does not render to `true`, or another workflow already owns the event's issue |
| Every issue claimed, including ones you meant for another workflow | No `when:` guard; filter on `event.assignee` or `event.source.id` |
| `foreach` iterates characters | `items:` has text around the expression, so it rendered to a string |
| A step is skipped on a second identical event | Expected: same event identity means replay. A genuinely new occurrence has a different identity |
| Workflow missing at startup | Failed validation; the log line names the action or capability |
| Comment posted to the wrong system | Split connectors: name one with `target:`, or let it default to `event.source.id` |
