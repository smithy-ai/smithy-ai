# YAML Workflows

Smithy workflows are event-driven state machines written in YAML. Use them to describe how Smithy should react to issues, pull requests, CI events, and comments without putting orchestration logic in Java.

Workflow YAML should stay declarative. Webhook parsing, Docker isolation, VCS calls, agent sessions, recovery, and credential handling remain owned by the orchestrator. The YAML file chooses events, states, and typed steps.

## File layout

Repository-owned workflows live in `.smithy/workflows`:

```text
.smithy/
  workflows/
    smithy.yml
    architect-review.yml
  prompts/
    refinement.md.j2
    building.md.j2
```

Global workflows can be mounted under `/config/workflows`. Built-in workflows ship with the orchestrator image. Loading order is:

1. built-in workflows,
2. global workflows from `/config/workflows`,
3. repository workflows from `.smithy/workflows`.

If two workflows use the same `metadata.name`, the later source overrides the earlier source.

## Minimal Workflow

```yaml
apiVersion: smithy.ai/v1alpha1
kind: Workflow

metadata:
  name: smithy-development

defaults:
  tools:
    refine: [Read, Write, Glob, Grep, Bash]
    build: [Read, Edit, Write, Bash]
  instanceKey: "smithy.{{ repo.owner }}.{{ repo.name }}.{{ issue.number }}"

routing:
  - event: issue.assigned
    action: create
    key: "{{ defaults.instanceKey }}"
  - event: [issue.comment, issue.plan_approved]
    action: dispatch
    key: "{{ smithy.issueKey(event) }}"
  - event: [issue.unassigned, pr.unassigned]
    action: destroy
    key: "{{ smithy.issueKey(event) }}"

state:
  initial: refine
  terminal: done

  refine:
    on:
      issue.assigned:
        steps:
          - uses: container.init
            with:
              cloneUrl: "{{ repo.cloneUrl }}"
              branch: "{{ smithy.branch(issue.number, issue.title) }}"
              sourceBranch: "{{ issue.baseBranch }}"
              workflowType: smithy

          - uses: agent.run
            id: plan
            with:
              mode: plan
              tools: "{{ defaults.tools.refine }}"
              prompt: .smithy/prompts/refinement.md.j2
              context:
                issue_number: "{{ issue.number }}"
                issue_title: "{{ issue.title }}"

          - uses: git.commitPush
            with:
              message: "Development plan for #{{ issue.number }}"

      issue.plan_approved:
        to: build
        steps:
          - uses: pr.create
            id: pr
            with:
              title: "{{ issue.title }}"
              head: "{{ smithy.branch(issue.number, issue.title) }}"
              base: "{{ issue.baseBranch }}"
              draft: true
              body: "fixes #{{ issue.number }}"

          - uses: agent.run
            with:
              mode: new
              tools: "{{ defaults.tools.build }}"
              prompt: .smithy/prompts/building.md.j2
              context:
                issue_number: "{{ issue.number }}"
                issue_title: "{{ issue.title }}"

          - uses: agent.ensureCommitted
          - uses: git.pushWithRetry
            with:
              pr: "{{ steps.pr.number }}"

  build:
    on:
      pr.finalized:
        to: done
        steps:
          - uses: git.cleanupBranch
          - uses: instance.destroy

  done:
    on: {}
```

## How To Build One

1. Pick a stable instance key. The same issue or PR should resolve to the same `key` across all routing rules.
2. Add routing rules for the events that create, resume, destroy, or ignore the workflow.
3. Define `state.initial`, optional `state.terminal`, and one object for each stage.
4. Under each stage, add `on` transitions keyed by canonical event name.
5. Compose each transition from typed steps. Give a step an `id` when later steps need its outputs.

Events that are not listed in the current stage are ignored for that workflow instance.

## Top-Level Fields

| Field | Required | Description |
|---|---:|---|
| `apiVersion` | Yes | Must be `smithy.ai/v1alpha1`. |
| `kind` | Yes | Must be `Workflow`. |
| `metadata.name` | Yes | Stable workflow name. Used for overrides and persisted state. |
| `metadata.extends` | No | Optional base workflow name for workflow inheritance. |
| `defaults` | No | Free-form values available as `defaults` in expressions. |
| `routing` | No | List of event routing rules. An empty list is valid. |
| `state` | Yes | State machine definition. |
| `actions` | No | Reusable composite actions made from normal steps. |

Workflow YAML is strict. Unknown fields are rejected except where a map is expected, such as `defaults`, step `with`, and action `inputs`.

## Routing

Routing decides what to do when a canonical event arrives.

```yaml
routing:
  - event: [issue.comment, ci.failure]
    action: dispatch
    key: "{{ smithy.issueKey(event) }}"
```

| Field | Required | Description |
|---|---:|---|
| `event` | Yes | A single event name or a list of event names. Blank names are invalid. |
| `action` | Yes | One of `create`, `dispatch`, `destroy`, or `ignore`. |
| `key` | For all except `ignore` | Expression that resolves to the workflow instance key. |

Routing actions:

| Action | Meaning |
|---|---|
| `create` | Create a workflow instance with `key`, then pass the event to it. |
| `dispatch` | Send the event to an existing workflow instance with `key`. |
| `destroy` | Destroy the workflow instance with `key`. |
| `ignore` | Drop the event for this workflow. No `key` is required. |

## State Machine

`state` contains the current stage and the event transitions allowed in each stage.

```yaml
state:
  initial: refine
  terminal: done

  refine:
    on:
      issue.comment:
        steps:
          - uses: agent.run

      issue.plan_approved:
        to: build
        steps:
          - uses: state.set
            with:
              state: build

  build:
    on: {}

  done:
    on: {}
```

Rules:

| Rule | Description |
|---|---|
| `state.initial` is required | It must name a stage defined under `state`. |
| `state.terminal` is optional | If set, it must name a defined stage. |
| A stage is any object under `state` except `initial` and `terminal` | Each stage may define `on`. |
| `on` maps event names to transitions | The transition runs only when the instance is currently in that stage. |
| `to` is optional | If omitted, the workflow remains in the current stage. If present, it must name a defined stage. |
| `steps` is optional | If omitted, the transition has no side effects beyond the state change. |

## Step Syntax

Steps run sequentially inside a transition.

```yaml
- uses: agent.run
  id: build
  if: "{{ !state.ciPaused }}"
  with:
    mode: resume
    prompt: .smithy/prompts/review_comment.md.j2
```

| Field | Required | Description |
|---|---:|---|
| `uses` | Yes | First-party step name or a composite action name. |
| `id` | No | Unique name for this step within the same transition or composite action. Outputs become `steps.<id>`. |
| `if` | No | Jinja expression. If false, the step is skipped. |
| `with` | No | Input object for the selected step. The action validates its own fields. |

Step IDs only need to be unique within their local `steps` list.

## Built-In Steps

These are the first-party step names workflows should use.

| Step | Common `with` fields | Outputs | Purpose |
|---|---|---|---|
| `container.init` | `cloneUrl`, `branch`, `sourceBranch`, `workflowType`, `gitEmail`, `gitUsername`, `vcsToken`, `extraRepos`, `cacheVolumes` | Container metadata | Create the task container and write initial workflow state. |
| `attachments.fetch` | `issue` | `files` | Download issue attachments and copy them into the task container. |
| `agent.run` | `mode`, `tools`, `prompt`, `context`, `model` | `sessionId`, `latestPlanFile`, parsed response fields | Render a prompt and run the configured agent provider, Claude Code or Codex. |
| `agent.ensureCommitted` | None | None | Ask the agent to commit uncommitted changes. |
| `file.copy` | `from`, `to` | None | Copy a file inside the task container. |
| `exec` | `command`, `env`, `cwd` | `exitCode`, `stdout`, `stderr` | Run an explicit command inside the task container. |
| `git.pull` | `mode` | `exitCode`, `stdout`, `stderr` | Pull from the remote, usually with `mode: ff-only`. |
| `git.rebase` | `branch`, `onto` | Command result | Rebase the work branch onto a base branch. |
| `git.commitPush` | `message` | Command result | Commit and push using the Smithy helper script. |
| `git.pushWithRetry` | `pr` | Command result | Push changes and handle common non-fast-forward conflicts. |
| `git.cleanupBranch` | None | Command result | Remove planning artifacts before finalization. |
| `issue.comment` | `issue`, `body` | Comment metadata | Create an issue comment. |
| `pr.create` | `title`, `head`, `base`, `body`, `draft` | `number`, PR metadata | Create a pull request. |
| `pr.comment` | `pr`, `body` | Comment metadata | Create a PR conversation comment. |
| `pr.review` | `pr`, `body`, `event`, `comments` | Review metadata | Create a PR review, optionally with inline comments. |
| `pr.requestReview` | `pr`, `reviewers` | None | Request review from humans or bots. |
| `state.set` | `state`, optional `vars` | Updated state | Set the current workflow stage or persisted variables. |
| `state.touch` | None | Updated state | Update the recovery timestamp. |
| `ci.retryGuard` | `maxAttempts`, `approvalComment` | `allowed`, `attempts`, `paused` | Increment CI retry state and pause after the configured limit. |
| `ci.reset` | None | Updated CI state | Reset CI retry state after recovery. |
| `instance.destroy` | None | None | Remove the in-memory workflow instance and destroy its container. |

Prefer first-party steps for VCS, issue tracker, state, and container operations. Use `exec` for repository-specific commands inside the isolated task container, not for host-level orchestration.

## Composite Actions

Use `actions` to name a reusable sequence of steps.

```yaml
actions:
  smithy.resumeBuild:
    inputs:
      prompt: string
    steps:
      - uses: agent.run
        with:
          mode: resume
          tools: "{{ defaults.tools.build }}"
          prompt: "{{ inputs.prompt }}"
      - uses: agent.ensureCommitted
      - uses: git.pushWithRetry
```

Composite actions use the same step syntax and validation rules as transitions. Call them from a transition with `uses: smithy.resumeBuild`.

## Canonical Events

Webhook provider adapters normalize Forgejo and GitLab payloads into these workflow event names.

| Event | Available context | Typical use |
|---|---|---|
| `issue.assigned` | `repo`, `issue` | Start work when Smithy is assigned to an issue. |
| `issue.unassigned` | `repo`, `issue` | Stop work when Smithy is unassigned. |
| `issue.comment` | `repo`, `issue`, `event.comment` | Refine a plan from issue feedback. |
| `issue.plan_approved` | `repo`, `issue`, `event.approver` | Move from planning to implementation. |
| `human.push` | `repo`, `event.branch` | Refresh the task container after a human pushed to the work branch. |
| `pr.conversation_comment` | `repo`, `pr`, `event.comment` | Respond to a PR conversation comment. |
| `pr.review_comment` | `repo`, `pr`, `event.comments` | Respond to inline PR review comments. |
| `pr.review_submitted` | `repo`, `pr`, `event.review` | Respond to a submitted PR review. |
| `pr.finalized` | `repo`, `pr` | Clean up when a draft PR is marked ready. |
| `pr.unassigned` | `repo`, `pr` | Stop work when Smithy is unassigned from a PR. |
| `pr.review_requested` | `repo`, `pr` | Start Architect review. |
| `pr.merged` | `repo`, `pr` | Start Architect learning. |
| `pr.closed` | `repo`, `event.pr` | Stop related workflows after a PR is closed without merge. |
| `ci.failure` | `repo`, `ci` | Ask the agent to fix failing CI. |
| `ci.recovery` | `repo`, `ci` | Reset CI retry state. |

## Expressions And Context

String values may contain Jinja expressions.

| Name | Contents |
|---|---|
| `event` | Canonical event payload. |
| `repo` | Owner, repo name, clone URL, default branch, and external URL. |
| `issue` | Issue details for issue-scoped events. |
| `pr` | Pull request details for PR-scoped events. |
| `ci` | CI run details for CI events. |
| `state` | Persisted workflow state, CI retry state, and variables. |
| `steps` | Outputs from previous steps in the same transition. |
| `defaults` | Values from the workflow `defaults` block. |
| `vars` | Repository or workflow variables. |
| `inputs` | Composite action inputs. Present only inside `actions`. |

Helpers:

| Helper | Purpose |
|---|---|
| `smithy.branch(number, title)` | Build the standard Smithy work branch name. |
| `smithy.planPath(number)` | Build the standard plan file path. |
| `smithy.issueNumber(branch)` | Parse an issue number from a Smithy or Architect branch. |
| `smithy.issueKey(event)` | Resolve the workflow instance key for issue, PR, push, or CI events. |
| `smithy.contextRepo(repo)` | Resolve the configured context repository, falling back to `<repo>-context`. |
| `vcs.fileUrl(path)` | Build a browser URL for a file on the workflow branch. |

Keep expressions small. Put side effects in typed steps, not in templates.

## Validation Checklist

Before a workflow can run, it must pass these checks:

- `apiVersion` is exactly `smithy.ai/v1alpha1`.
- `kind` is exactly `Workflow`.
- `metadata.name` is present.
- Every routing rule has a non-empty `event` and an `action`.
- `create`, `dispatch`, and `destroy` routing rules have a `key`.
- `state.initial` names a defined stage.
- `state.terminal`, when present, names a defined stage.
- Every transition `to`, when present, names a defined stage.
- Every step has `uses`.
- Step `id` values are unique within the same step list.
