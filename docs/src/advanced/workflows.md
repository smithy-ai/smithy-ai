# Workflows

A workflow is a YAML definition executed against a durable run. Smithy ships some
built in; you can override those or add your own without writing Java.

## Where definitions come from

Definitions are read at startup, in this order — later ones win by name:

1. **Built in**, from the orchestrator's own jar.
2. **`workflow.definitions-dir`** (`WORKFLOW_DIR`, default `/config/workflows`) — mount
   a directory here and drop `.yml` files in it.
3. **A repository's own `.smithy/workflows/*.yml`**, read over the provider API
   when an event from that repository arrives. A team that wants its own flow
   does not need a file on the orchestrator's disk, any more than they need one
   to have CI. These are cached briefly and every failure is soft: a broken
   definition costs that workflow, not the repository's ability to be worked on.

Replacing a built-in means writing a file with the same `metadata.name`. A file
that fails to parse or validate is logged and skipped; it does not stop the
others loading.

## Anatomy

```yaml
apiVersion: smithy.ai/v1alpha1
kind: Workflow
metadata:
  name: my-workflow

vars:                       # constants every step can read as `vars.x`
  reviewers: [alice]

routing:                    # which events belong to this workflow, and to which run
  - event: issue.assigned
    when: "{{ repo.fullName == 'acme/api' }}"
    action: create          # create | dispatch | destroy | ignore
    key: "{{ repo.fullName }}#{{ event.issueRef }}"

state:
  initial: new
  terminal: done
  new:
    on:
      issue.assigned:
        to: working
        steps:
          - uses: issue.comment
            with:
              owner: "{{ repo.owner }}"
              repo: "{{ repo.name }}"
              issue: "{{ event.issueRef }}"
              body: "On it."
  working:
    on: {}
  done:
    on: {}
```

**Routing** decides which run an event belongs to. `key` is a template that must
resolve to the same string for the same piece of work; the first matching rule in
the file wins, so ordering is arbitration you can see. `when` is a predicate for
telling two workflows listening on the same event apart.

**State** is the machine. Each state names the events it handles, the steps to
run, and where to go next. Reaching `terminal` completes the run and releases
whatever it was holding.

## Expressions

`{{ ... }}` is Jinja, over a fixed set of names: `run`, `vars`, `steps`, `event`,
`repo`, and inside a `foreach`, `item` and `index`. A value that is exactly one
expression yields the object it names rather than its text, so
`items: "{{ vars.plan }}"` iterates a list.

`if:` on a step gates it. Anything that does not render to `true` is false —
conditions are deliberately not a boolean algebra. When a definition wants real
logic, that is the signal for a new action rather than more syntax.

## Steps

A step names an action with `uses:`, passes it a `with:` block, and optionally
takes an `id:` so later steps can read `steps.<id>.<field>`.

Transitions are resumable. An agent turn inside one can run for half an hour, so
an orchestrator restart mid-transition is normal rather than exceptional: every
step records its outcome, and re-running a transition skips the steps that
already completed and reuses their output. That is what stops a resume opening a
second pull request.

### Actions

| Action | Does |
|---|---|
| `container.init` | Give the run a container to work in |
| `agent.run` | One agent turn, returning its prose |
| `agent.runStructured` | An agent turn that must answer in a declared shape |
| `agent.ensureCommitted` | Ask the agent to commit what it left behind |
| `exec` | Run a command in the container |
| `git.pull`, `git.push`, `git.status` | Git, inside the container |
| `issue.create`, `issue.assign`, `issue.label`, `issue.comment`, `issue.read` | Issue tracker |
| `pr.create`, `pr.comment`, `pr.requestReview`, `pr.read` | Pull requests |
| `state.set`, `state.var` | Write to the run |
| `metrics.record` | Append to the run's history |
| `ci.retryGuard`, `ci.reset` | Bound how hard a failing pipeline is chased |
| `foreach` | Run nested steps once per item |
| `run.spawn`, `run.await`, `run.wave` | Child runs, and dependency-ordered waves |
| `gate.await`, `signal.emit`, `correlate` | Waiting, notifying, and routing |
| `instance.destroy` | Release the run's container |

Actions declare what a provider must support, and definitions are checked against
the configured provider at startup. A workflow needing an operation your provider
lacks is dropped with a message naming the action and the capability, instead of
failing halfway through someone's pull request.

### Declaring a structured answer

`agent.runStructured` builds its schema from the shape you write:

```yaml
- uses: agent.runStructured
  id: plan
  with:
    template: coordinator_plan.md.j2
    output:
      summary: string
      issues:
        - owner: string
          repo: string
          dependsOn: [integer]
```

A scalar is named by a type (`string`, `number`, `integer`, `boolean`); a map is a
nested object; a one-element list is an array of whatever that element describes.
The fields come back as the step's outputs.

## Coordinating work across repositories

`feature-coordinator` splits one story into per-repository issues, waits for a
human to approve the plan, then hands each issue out as its dependencies clear.

Children are **ordinary issues** in their target repositories. There is no
tracker-native subtask, epic or tasklist anywhere — not every tracker has them,
and the parent story may live in Jira while the work lives in GitLab. The
parent/child graph is in Smithy's own run store, which is also what routes an
event about a child issue back to the run that owns it.

It ships with an empty repository catalog, because the built-in cannot know your
repositories. Supply yours by extending it:

```yaml
# /config/workflows/acme-coordinator.yml
apiVersion: smithy.ai/v1alpha1
kind: Workflow
metadata:
  name: acme-coordinator
  extends: feature-coordinator
vars:
  catalog:
    - owner: acme
      repo: api
      description: The HTTP API
    - owner: acme
      repo: web
      description: The web client
  botUser: smithy
```

`extends` contributes variables only — routing and states come from the base — so
configuring a shipped workflow cannot quietly change what it does. A workflow that
extends another shadows it, since the base is a template rather than something to
run alongside its configured form.

### Ordering

Each planned issue carries `dependsOn`, a list of zero-based indexes into the
plan. An issue is only handed to an agent once everything it depends on has
finished. A dependency that was never created cannot block forever.
