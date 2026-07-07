# YAML Workflows

This is the proposed direction for moving Smithy workflows out of Java classes and into repository-owned YAML files.

The goal is not to make YAML execute arbitrary orchestration logic. The goal is to make common workflow composition easy while keeping the risky parts in typed, tested building blocks.

## Design goals

- Repository teams can configure or extend Smithy by committing workflow files.
- Existing Smithy flows can be expressed without custom Java per workflow.
- The orchestrator still owns webhook parsing, VCS clients, Docker isolation, prompt rendering, Claude execution, and recovery.
- Workflow files are validated before execution and are safe to reason about.
- Advanced teams can extend Smithy by adding new Java-backed actions instead of forking the runner.

## File layout

Repository-owned workflows should live under `.smithy/`:

```text
.smithy/
  workflows/
    smithy.yml
    architect-review.yml
    architect-learn.yml
  prompts/
    refinement.md.j2
    building.md.j2
```

Smithy should also ship built-in workflows in the orchestrator image. If a repository does not define `.smithy/workflows`, the built-in workflows remain the default. A repository workflow with the same `metadata.name` can override a built-in workflow, and a workflow can also `extends` a built-in workflow for small changes.

## Mental model

A workflow has four layers:

| Layer | Purpose |
|---|---|
| Events | Canonical events produced by Forgejo, GitLab, or later providers |
| Routing | Decide whether an event creates, dispatches to, or destroys a workflow instance |
| State machine | Decide which events are valid in the current state and what state comes next |
| Steps | Typed actions such as creating a container, rendering a prompt, running Claude, pushing, or commenting |

Webhook mapping should stay in Java. YAML should see provider-neutral event names such as `issue.assigned`, `issue.comment`, `issue.plan_approved`, `pr.review_comment`, `pr.finalized`, `ci.failure`, and `ci.recovery`.

## Example workflow

This is the shape of the current Smithy development workflow in YAML form. It is intentionally compact, but the steps are still explicit enough to see side effects.

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
  - event: [issue.comment, issue.plan_approved, pr.conversation_comment, pr.review_comment, pr.review_submitted, pr.finalized, ci.failure, ci.recovery]
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

          - uses: attachments.fetch
            id: attachments
            with:
              issue: "{{ issue.number }}"

          - uses: agent.run
            id: plan
            with:
              mode: plan
              tools: "{{ defaults.tools.refine }}"
              prompt: .smithy/prompts/refinement.md.j2
              context:
                issue_number: "{{ issue.number }}"
                issue_title: "{{ issue.title }}"
                issue_body: "{{ issue.body }}"
                plan_file_path: "{{ smithy.planPath(issue.number) }}"
                attachments: "{{ steps.attachments.files }}"

          - uses: file.copy
            with:
              from: "{{ steps.plan.latestPlanFile }}"
              to: "{{ smithy.planPath(issue.number) }}"

          - uses: git.commitPush
            with:
              message: "Development plan for #{{ issue.number }}"

          - uses: issue.comment
            with:
              issue: "{{ issue.number }}"
              body: "Development plan: [{{ smithy.planPath(issue.number) }}]({{ vcs.fileUrl(smithy.planPath(issue.number)) }})"

      issue.comment:
        steps:
          - uses: state.touch
          - uses: git.pull
            with:
              mode: ff-only
          - uses: attachments.fetch
            id: attachments
            with:
              issue: "{{ issue.number }}"
          - uses: agent.run
            with:
              mode: resume
              tools: "{{ defaults.tools.refine }}"
              prompt: .smithy/prompts/refinement_comment.md.j2
              context:
                issue_number: "{{ issue.number }}"
                comment_body: "{{ event.comment.body }}"
                plan_file_path: "{{ smithy.planPath(issue.number) }}"
                attachments: "{{ steps.attachments.files }}"
          - uses: git.commitPush
            with:
              message: "Update plan for #{{ issue.number }}"

      issue.plan_approved:
        to: build
        steps:
          - uses: state.set
            with:
              state: build
          - uses: git.rebase
            with:
              branch: "{{ smithy.branch(issue.number, issue.title) }}"
              onto: "{{ issue.baseBranch }}"
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
                plan_file_path: "{{ smithy.planPath(issue.number) }}"
          - uses: agent.ensureCommitted
          - uses: git.pushWithRetry
            with:
              pr: "{{ steps.pr.number }}"

  build:
    on:
      pr.conversation_comment:
        steps:
          - uses: state.touch
          - uses: agent.run
            with:
              mode: resume
              tools: "{{ defaults.tools.build }}"
              prompt: .smithy/prompts/review_comment.md.j2
              context:
                pr_number: "{{ pr.number }}"
                issue_number: "{{ smithy.issueNumber(pr.headBranch) }}"
                comments:
                  - user: "{{ event.comment.user }}"
                    body: "{{ event.comment.body }}"
          - uses: agent.ensureCommitted
          - uses: git.pushWithRetry
            with:
              pr: "{{ pr.number }}"

      ci.failure:
        steps:
          - uses: ci.retryGuard
            id: retry
            with:
              maxAttempts: 5
              approvalComment: "Reached maximum 5 CI fix attempts. Continue debugging? Reply with OK to continue."
          - uses: agent.run
            if: "{{ steps.retry.allowed }}"
            with:
              mode: resume
              tools: "{{ defaults.tools.build }}"
              prompt: .smithy/prompts/ci_failure.md.j2
              context:
                pr_number: "{{ ci.prNumber }}"
                issue_number: "{{ smithy.issueNumber(ci.headBranch) }}"
                workflow_id: "{{ ci.workflowName }}"
          - uses: agent.ensureCommitted
            if: "{{ steps.retry.allowed }}"
          - uses: git.pushWithRetry
            if: "{{ steps.retry.allowed }}"
            with:
              pr: "{{ ci.prNumber }}"

      ci.recovery:
        steps:
          - uses: ci.reset

      pr.finalized:
        to: done
        steps:
          - uses: git.cleanupBranch
          - uses: instance.destroy
```

## Building blocks

Workflow YAML should compose typed actions. Each action has a JSON-schema-backed `with` contract, returns typed outputs under `steps.<id>`, and declares whether it requires a container, a Claude session, VCS credentials, or issue-tracker credentials.

Initial action catalog:

| Action | Responsibility |
|---|---|
| `container.init` | Create the task container and write initial workflow state |
| `attachments.fetch` | Download issue attachments and copy them into the container |
| `agent.run` | Render a prompt and start, resume, or replace a Claude session |
| `agent.ensureCommitted` | Ask Claude to commit uncommitted changes |
| `file.copy` | Copy a file inside the task container |
| `exec` | Run an explicit command in the task container |
| `git.pull` | Pull with a constrained mode such as `ff-only` |
| `git.rebase` | Rebase the work branch onto a base branch |
| `git.commitPush` | Commit and push via the existing Smithy helper |
| `git.pushWithRetry` | Push, handling common non-fast-forward conflicts |
| `git.cleanupBranch` | Remove planning artifacts before finalization |
| `issue.comment` | Create an issue comment |
| `pr.create` | Create a pull request |
| `pr.comment` | Create a PR conversation comment |
| `pr.review` | Create a PR review with optional inline comments |
| `pr.requestReview` | Request a human or bot review |
| `state.set` | Set the current workflow state |
| `state.touch` | Update the recovery timestamp |
| `ci.retryGuard` | Increment CI retries and pause after a configured limit |
| `ci.reset` | Reset CI retry state |
| `instance.destroy` | Remove the in-memory instance and destroy its container |

This keeps YAML easy to read while avoiding ad hoc shell scripts for every VCS and orchestration operation. Teams can still use `exec` for repository-specific commands inside the isolated task container.

## Expression and context model

Strings in YAML use the same Jinja syntax already used for prompts. The runtime should expose a small, documented context:

| Name | Contents |
|---|---|
| `event` | Raw canonical event payload |
| `repo` | Owner, repo name, clone URL, default branch, external URL |
| `issue` | Present for issue-scoped events |
| `pr` | Present for PR-scoped events |
| `ci` | Present for CI events |
| `state` | Persisted workflow state and variables |
| `steps` | Outputs from previous steps in the same transition |
| `defaults` | Values from the workflow `defaults` block |
| `vars` | Repository or workflow variables |

Helper functions should cover Smithy-specific naming and routing:

| Helper | Purpose |
|---|---|
| `smithy.branch(number, title)` | Current `smithy/<issue>-<slug>` branch naming |
| `smithy.planPath(number)` | Current `.smithy/plans/<issue>.md` path |
| `smithy.issueNumber(branch)` | Parse an issue number from a Smithy or Architect branch |
| `smithy.issueKey(event)` | Resolve the workflow instance key for issue, PR, push, or CI events |
| `smithy.contextRepo(repo)` | Resolve the configured context repository, falling back to `<repo>-context` |
| `vcs.fileUrl(path)` | Build a browser URL for a file on the workflow branch |

Conditions should be deliberately small. Prefer `if: "{{ steps.retry.allowed }}"` and typed event filters over embedding a full programming language.

## Extension model

Extensions should be Java-backed actions, not arbitrary host scripts. A new action implements a small SPI:

```java
public interface WorkflowAction {
    String type();
    Class<?> inputType();
    Class<?> outputType();
    WorkflowActionResult execute(WorkflowActionContext context, Object input);
}
```

The runner discovers actions as Spring beans. This gives us:

- validation through Jackson and JSON Schema,
- consistent logging and redaction,
- a testable unit for every side effect,
- a clear place to enforce credentials and container boundaries.

Composite actions can later be added in YAML:

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

## Recovery requirements

The current `ContainerState` stores stage, Claude session ID, CI retry state, and timestamps. YAML workflows need a little more metadata:

```yaml
workflowName: smithy-development
workflowVersion: sha256-of-normalized-yaml
state: build
sessionId: claude-session-id
vars:
  prNumber: 123
ciRetryCount: 2
ciPaused: false
lastProcessedAt: 2026-07-07T10:00:00Z
```

On startup, the orchestrator should:

1. list managed containers,
2. read their persisted workflow metadata,
3. reload the matching workflow definition,
4. verify the workflow version still has the persisted state,
5. recreate the generic workflow instance.

If the workflow file changed incompatibly, the instance should remain paused and visible in logs rather than guessing.

## Loading and precedence

Recommended loading order:

1. Built-in workflows from the orchestrator image.
2. Optional global workflows from `/config/workflows`.
3. Repository workflows from `.smithy/workflows` on the repository default branch.

Repository workflows should be cached by repository and commit SHA. The orchestrator needs the workflow definition before creating the task container, so repository workflows should be fetched through the VCS API, not by relying on the task container clone.

## Migration plan

1. Add the workflow model, loader, schema generator, and validator.
2. Add the action SPI and port existing helper behavior into actions.
3. Add a generic `YamlWorkflowFactory` and `YamlWorkflowInstance`.
4. Recreate the current Smithy workflow as a built-in YAML file and run parity tests against the Java flow.
5. Recreate Architect review and learning workflows as built-in YAML files.
6. Keep Java workflows behind a fallback flag for one release.
7. Make repository-owned workflows opt-in, then make built-in YAML workflows the default.

## Testing strategy

- Parser tests for valid and invalid workflow files.
- Golden tests for routing: each canonical event should create, dispatch, destroy, or ignore exactly as expected.
- State-machine tests for allowed and ignored transitions.
- Action unit tests with fake Docker, Claude, VCS, and issue clients.
- Recovery tests for compatible and incompatible workflow versions.
- One end-to-end demo test that runs the built-in Smithy workflow against a fake repository service.

## Non-goals

- YAML should not replace webhook provider adapters.
- YAML should not call host commands.
- YAML should not embed Java, Groovy, JavaScript, or unbounded expression evaluation.
- The first version does not need arbitrary parallel execution. Sequential transitions are enough for current Smithy behavior and easier to recover.
