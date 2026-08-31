# Day to day

What you actually do once an instance is [set up](setup/demo.md). This describes
the workflows that ship with Smithy-AI; all of them are
[definitions you can change](workflows/index.md).

## Working one issue: `smithy-development`

### 1. Create an issue

Create an issue in your repository describing the feature or bug you want to address. Write it as you would for a human developer, with context, acceptance criteria, and any relevant details.

### 2. Assign to Agent Smithy

Assign the issue to the username configured for the `smithy` actor on that connector. This triggers Smithy to:

- Create a new branch for the issue
- Analyze the codebase and write a plan
- Post the plan as a comment on the issue

### 3. Review the plan

Read through Smithy's plan on the issue. You can:

- Add comments to request changes or clarify requirements
- Smithy will update the plan based on your feedback

Take your time here. A good plan leads to a good implementation.

### 4. Approve the plan

When you're satisfied with the plan, add the **"Plan Approved"** label to the issue. This triggers the implementation phase.

### 5. Implementation

Smithy creates a draft pull request and begins implementing the plan. During this phase, Smithy:

- Writes code according to the plan
- Monitors CI status and fixes failures
- Updates the PR as work progresses

### 6. Review

Once implementation is complete, review the pull request. You have two options:

- **Review it yourself**: read the code, leave comments, request changes
- **Request review from The Architect**: assign The Architect as a reviewer on the PR for an automated review against project best practices

Smithy responds to review comments and iterates on the code.

### 7. Finalize

When you're satisfied with the implementation, remove the draft status from the PR. Smithy cleans up planning artifacts and the work is done. Merge when ready.

## Working a feature across repositories: `feature-coordinator`

When a change spans several repositories, assign the story to the **coordinator**
actor instead of to smithy. It plans the split, creates an ordinary issue in each
repository, and hands them out in dependency order, each one worked by an
ordinary `smithy-development` run.

The coordinator has to be told which repositories it may work with before it
will claim anything. See
[coordinating across repositories](workflows/coordinator.md).

Who you assign an issue to is how you choose between the two: **coordinator**
means "plan this across repositories", **smithy** means "do this here".

## The Architect

The Architect is a separate agent focused on code review and maintaining project knowledge.

### Automated review

When you request a review from The Architect on a pull request, it reviews the code against established best practices stored in the context repository.

### Learning flow

After a PR is merged or rejected, The Architect scans the human review comments. If the feedback contains lessons worth preserving, The Architect:

1. Opens a PR on the context repository with updated knowledge
2. You review and merge (or request changes to) this knowledge update

This creates a feedback loop where project standards evolve based on real review history.

### Context repository

Each repository can have a companion context repository. By default Smithy looks for `<repo>-context`. This repository stores project knowledge as markdown files: coding standards, architectural decisions, common patterns, and lessons learned from past reviews. The Architect uses this knowledge base when reviewing PRs, and the knowledgebase MCP is scoped to the same repository.

To use a different context repository, add `.smithy/config.yml` to the source repository:

```yaml
context:
  repository: shared-guidelines
```

Use `owner/repo` when the context repository lives under a different owner or group:

```yaml
context:
  repository: platform/engineering-guidelines
```

Follow-up comments on the pull request The Architect opens against the context
repository are correlated directly to its learning run, so explicitly named and
cross-owner context repositories support the complete review conversation.

The context repository is created automatically by `setup_repo.py` during [demo setup](setup/demo.md), or you can create it manually.

## Watching and steering a run

The dashboard lists **runs**: every piece of work, including finished and failed
ones, because a run outlives the container that did it. Open one to see its
timeline: every event it received and every milestone it recorded, in order.

From there you can:

- **Approve a gate** a run is holding at, which is the same as applying the
  approval label.
- **Cancel a run.** The container goes; the history stays. Anything waiting on it
  keeps waiting rather than treating it as delivered. Assigning the issue again
  puts the same run back to work, starting over from its first step.
- **Take over the session** and talk to the agent yourself. While you hold it,
  inbound events are held rather than acted on, so the agent is not working on
  top of what you are typing. Control lapses on its own if you close the tab.

    Messages can carry screenshots — paste one straight into the composer, drop
    it on, or pick it with the image button, up to five per message. Each is
    saved into the run's container under `.smithy/tmp/takeover/` and named in
    the prompt, so the agent opens it as it would any other file. PNG, JPEG, GIF
    and WebP, up to 10MB each. Showing a broken layout beats describing one.

## Labels

| Label | Purpose |
|---|---|
| **Plan Approved** | Added to an issue to trigger the implementation phase |

The label is configurable (`workflow.plan-approved-label`), and a workflow can
treat something else as approval instead. Nothing outside the workflow definition
depends on this particular label.
