# Workflow reference

Complete surface: schema, expression context, events, actions. Written to be read
end to end by a person or a model that is authoring a workflow.

If you are handing this to an LLM, this page alone is enough to write a valid
workflow against.

## Schema

```yaml
apiVersion: smithy.ai/v1alpha1     # required, exactly this
kind: Workflow                     # required, exactly this

metadata:
  name: my-workflow                # required, unique; later definitions win by name
  version: "1"                     # optional; recorded on runs, drift is surfaced
  extends: other-workflow          # optional; inherits routing+state, may only add vars

vars:                              # optional; constants, readable as vars.x
  anything: you-like

actions:                           # optional; named step lists a transition can `uses:`
  someName:
    steps: [ ... ]

routing:                           # required unless `extends` is set
  - event: issue.assigned          # a name, or a list of names
    when: "{{ ... }}"              # optional predicate; must render to `true`
    action: create                 # create | dispatch | destroy | ignore
    key: "{{ ... }}"               # required unless `by:` is set
    by: pr                         # alternative to key: issue | pr | branch | container

state:                             # required unless `extends` is set
  initial: new                     # required; the state a new run starts in
  terminal: done                   # optional; reaching it completes the run
  <state-name>:
    on:
      <event-name>:
        to: <state-name>           # optional; stay put if absent
        debounce: 30s              # optional; ms/s/m — collect a burst into one transition
        steps:
          - uses: <action>         # required
            id: <name>             # optional; needed to read steps.<id>.*
            if: "{{ ... }}"        # optional; must render to `true`
            with: { ... }          # action inputs
            steps: [ ... ]         # nested, for `foreach`
```

### Routing actions

| `action` | Meaning |
|---|---|
| `create` | Start a run if none exists for this key. Stands aside if another workflow's run already owns the thing the event is about |
| `dispatch` | Deliver to an existing run; ignored if there is none |
| `destroy` | End the run as **cancelled** — work waiting on it keeps waiting |
| `ignore` | Match and stop, so a later rule cannot claim it |

## Expression context

| Name | Contents |
|---|---|
| `run` | `id`, `workflow`, `state` |
| `vars` | The workflow's `vars`, plus everything `state.var` has written |
| `steps` | `steps.<id>.<field>` — outputs of earlier steps in this transition |
| `event` | See [events](#events) |
| `repo` | `source`, `owner`, `name`, `fullName`, `cloneUrl` |
| `item`, `index` | Inside a `foreach` only |

### Filters

| Filter | Does | Example |
|---|---|---|
| `slug` | Lowercase, non-alphanumerics to `-`, max 40 chars | `{{ event.issueTitle \| slug }}` → `add-a-greeting` |
| `displayRef` | `#` prefix for numeric refs; tracker keys unchanged | `7` → `#7`, `ECD-4309` → `ECD-4309` |

Jinja's own filters (`length`, `default`, …) are available.

### Two rules that catch people out

**A value that is exactly one expression yields the object it names**, not its
text — so `items: "{{ vars.plan }}"` iterates a list. Add any surrounding text
and you get a string.

**`if:` and `when:` must render to the literal `true`.** `{{ event.approver }}`
renders `alice`, which is false. Write `{{ event.approver != '' }}`.

## Events

Every event has `event.name` and `event.source` (the connector: `forgejo`,
`gitlab`, `github`, `jira`), and `repo.*`.

### Issue events

| Name | Extra fields |
|---|---|
| `issue.assigned` | `issueRef`, `issueTitle`, `issueBody`, `baseBranch`, `assignee` |
| `issue.unassigned` | `issueRef`, `issueTitle`, `issueBody`, `baseBranch`, `assignee` |
| `issue.commented` | above, plus `commentBody` |
| `issue.plan_approved` | above, plus `approver` |

`assignee` is which of this deployment's actors the issue was handed to. Filtering
on it is how a feature for the coordinator is told from a task for the agent.

### Pull-request events

| Name | Extra fields |
|---|---|
| `pr.commented` | `prNumber`, `prTitle`, `headBranch`, `baseBranch`, `commentBody`, `commentUser`, `commentId`, `discussionId` |
| `pr.review_commented` | `prNumber`, `prTitle`, `headBranch`, `baseBranch`, `comments`, `commentId`, `discussionId` |
| `pr.review_submitted` | `prNumber`, …, `reviewId`, `reviewBody`, `reviewer` |
| `pr.review_requested` | `prNumber`, `prTitle`, `headBranch`, `baseBranch` |
| `pr.ready_for_review` | as above — emitted when a draft/WIP marker is removed |
| `pr.unassigned` | as above |
| `pr.merged` | as above |
| `pr.closed` | `prNumber`, `headBranch` |

### Push and CI

| Name | Extra fields |
|---|---|
| `push.human` | `branch` |
| `ci.failed` | `branch`, `workflowId` |
| `ci.recovered` | `branch` |

### Internal

| Name | Extra fields |
|---|---|
| `signal:<name>` | `signal`, plus every key of the payload |

Emitted by `signal.emit`, by the engine when a child run reaches a terminal state
(`signal:child-done`, payload includes `child`, `workflow`, `status` and the
child's vars), and by the dashboard when a gate is approved
(`signal:gate-approved`, payload includes `key`).

### Batching

A transition with `debounce:` receives one event covering the burst. It reads as
its most recent member, plus `event.batch` (a list of the individual event views)
and `event.batchSize`.

## Actions

Inputs are given in a step's `with:`. Outputs are read as `steps.<id>.<field>`.

**Every issue action** also accepts `connector:` (which system — defaults to
`event.source`) and `as:` (which actor — defaults to the workflow's `vars.actor`).

### Environment and agent

| Action | Required | Optional | Outputs |
|---|---|---|---|
| `container.init` | `name`, `cloneUrl` | `branch` (empty = remote default), `sourceBranch`, `stage`, `gitEmail`, `gitUsername`, `vcsToken`, `extraRepos[]` (`cloneUrl`, `path`, `branch`) | `name`, `created` |
| `agent.run` | `prompt` or `template` | `mode` (`plan`), `tools[]`, `vars{}`, `contextRepo` | `reply`; in plan mode `planFile`, `hasPlan` |
| `agent.runStructured` | `output{}`, and `prompt` or `template` | `tools[]`, `vars{}`, `contextRepo` | the declared fields |
| `agent.ensureCommitted` | — | `tools[]` | `committed` |
| `agent.newSession` | — | — | `reset` |
| `exec` | `command[]` or `shell` | `env{}`, `failOnError` (default true) | `exitCode`, `ok`, `stdout`, `stderr` |
| `instance.destroy` | — | — | `destroyed` |

`agent.run` in plan mode starts a fresh conversation; otherwise it resumes the
run's existing one. `agent.newSession` forces the next turn to start over —
useful when planning and building want different tools and no shared history.

#### Declaring a structured answer

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

A scalar names a type (`string`, `number`, `integer`, `boolean`); a map is a
nested object; a **one-element list** is an array of whatever that element
describes. Every declared field is required.

### Git

| Action | Required | Optional | Outputs |
|---|---|---|---|
| `git.pull` | — | `strategy` (`rebase` default, `ff-only`, `merge`) | `exitCode`, `ok`, `stdout`, `stderr` |
| `git.push` | — | `tools[]` | `pushed`, `retried`, `error` |
| `git.status` | — | — | `clean`, `branch`, `changes` |

`git.push` asks the agent to reconcile and retries once if the first push fails,
then reports rather than throws.

### Issues

| Action | Required | Optional | Outputs |
|---|---|---|---|
| `issue.create` | `owner`, `repo`, `title` | `body`, `labels[]` | `issueRef`, `title`, `baseBranch` |
| `issue.assign` | `owner`, `repo`, `issue` | `assignees[]` (or `to`) | `assignees` |
| `issue.label` | `owner`, `repo`, `issue`, `label` or `labels[]` | — | `labels` |
| `issue.comment` | `owner`, `repo`, `issue`, `body` | — | `commentId` |
| `issue.read` | `owner`, `repo`, `issue` | — | `issueRef`, `title`, `body`, `state`, `assignees`, `labels`, `baseBranch` |
| `attachments.fetch` | `owner`, `repo`, `issue` | — | `paths`, `count` |

### Pull requests

| Action | Required | Optional | Outputs |
|---|---|---|---|
| `pr.create` | `owner`, `repo`, `title`, `head`, `base` | `body`, `draft` | `number`, `title`, `headRef`, `baseRef`, `reused` |
| `pr.comment` | `owner`, `repo`, `number`, `body` | — | `number` |
| `pr.reply` | `owner`, `repo`, `number`, `body` | `discussion` | `posted`, `threaded` |
| `pr.review` | `owner`, `repo`, `number` | `summary`, `comments[]` (`path`, `line`, `body`), `event` | `posted`, `comments` |
| `pr.reviewComments` | `owner`, `repo`, `number` | `reviewId`, `reviewer`, `body` | `comments`, `count` |
| `pr.conversation` | `owner`, `repo`, `number` | — | `entries`, `count` |
| `pr.read` | `owner`, `repo`, `number` | — | `number`, `title`, `body`, `merged`, `headRef`, `baseRef`, `assignees` |
| `pr.findByHead` | `owner`, `repo`, `head` | — | `found`, `number`, `title`, `merged`, `assignees` |
| `pr.isAssigned` | `owner`, `repo`, `number`, `user` | — | `assigned` |
| `pr.setAssignees` | `owner`, `repo`, `number` | `assignees[]` | `assignees` |
| `pr.requestReview` | `owner`, `repo`, `number` | `reviewers[]`, `notFrom` | `requested`, `reviewers`, `reason` |
| `pr.link` | `owner`, `repo`, `number` | — | `url` |
| `comment.react` | `owner`, `repo`, `number`, `commentId` | `reaction` (default `eyes`) | `reacted` |

`pr.create` reuses an existing pull request for the same head branch rather than
opening a second. `pr.requestReview` drops `notFrom` from the list and never
fails the transition — the branch is already pushed.

### Files and repositories

| Action | Required | Optional | Outputs |
|---|---|---|---|
| `file.url` | `owner`, `repo`, `branch`, `path` | — | `url` |
| `file.delete` | `owner`, `repo`, `branch`, `path` | `message` | `deleted` |
| `repo.cloneUrl` | `owner`, `repo` | — | `cloneUrl`, `fullName` |
| `repo.context` | `owner`, `repo` | — | `owner`, `repo`, `fullName`, `cloneUrl` |

`repo.context` reads where a repository keeps its guidelines from its own
`.smithy/config.yml`.

### Run state

| Action | Required | Optional | Outputs |
|---|---|---|---|
| `state.set` | `state` | — | `state` |
| `state.var` | any keys | — | the keys written |
| `metrics.record` | `name` | any keys | `recorded` |

`state.var` merges — writing one variable does not erase the rest.

### Control flow and coordination

| Action | Required | Optional | Outputs |
|---|---|---|---|
| `foreach` | `items[]`, nested `steps` | — | `count`, `results` |
| `run.spawn` | `workflow` | `state`, plus any keys, which become the child's vars | `runId`, `workflow` |
| `run.await` | — | `count` (a number, or `all`) | `satisfied`, `total`, `finished`, `failed`, `pending` |
| `run.wave` | — | — | `released[]`, `blocked[]`, `complete`, `total`, `finished`, `abandoned`, `pending` |
| `gate.await` | `key` | `kind` | `satisfied`, `key` |
| `signal.emit` | `signal` | `to` (`parent` default, or a run id), plus any keys as payload | `signal`, `to`, `released`, `handled` |
| `correlate` | `kind` (`issue`/`pr`/`branch`/`container`/`key`), `ref` | `run` (defaults to this run) | `kind`, `ref`, `run` |

`run.wave` reads each child's `index` and `dependsOn` vars and releases those
whose dependencies have **completed**. A cancelled child never satisfies a
dependency, and `complete` is true only when every child completed.

### CI

| Action | Required | Optional | Outputs |
|---|---|---|---|
| `ci.retryGuard` | — | `maxAttempts` (default 5), `autofix` | `proceed`, `reason`, `attempts` |
| `ci.reset` | — | — | `reset` |

`reason` is one of `ok`, `paused`, `autofix-disabled`, `attempts-exhausted`.

## Capabilities

Each action declares what a provider must support. A definition needing something
the configured provider cannot do is rejected at startup, naming the action and
the capability.

| Provider | Supports |
|---|---|
| GitLab | everything |
| Forgejo | pull requests, issue comment/assign/create/label, file read |
| GitHub | pull requests, issue comment/assign |
| Jira | issue comment, issue assign |

## Where definitions come from

Read at startup, later winning by name:

1. **Built in** — from the jar.
2. **`workflow.definitions-dir`** (`WORKFLOW_DIR`, default `/config/workflows`).
3. **A repository's `.smithy/workflows/*.yml`** — read over the provider API when
   an event from that repository arrives, cached briefly. Highest precedence.
   Failures are soft: a broken definition costs that workflow, not the
   repository's ability to be worked on.
