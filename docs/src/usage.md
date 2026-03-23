# Usage & Workflow

This page explains how to use Smithy-AI day-to-day once your instance is [set up](setup/demo.md).

## Smithy development workflow

### 1. Create an issue

Create an issue in your repository describing the feature or bug you want to address. Write it as you would for a human developer — include context, acceptance criteria, and any relevant details.

### 2. Assign to Agent Smithy

Assign the issue to the `smithy` user (or your configured `SMITHY_BOT_USER`). This triggers Smithy to:

- Create a new branch for the issue
- Analyze the codebase and write a plan
- Post the plan as a comment on the issue

### 3. Review the plan

Read through Smithy's plan on the issue. You can:

- Add comments to request changes or clarify requirements
- Smithy will update the plan based on your feedback

Take your time here — a good plan leads to a good implementation.

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

Each repository can have a companion context repository named `<repo>-context`. This repository stores project knowledge as markdown files — coding standards, architectural decisions, common patterns, and lessons learned from past reviews. The Architect uses this knowledge base when reviewing PRs.

The context repository is created automatically by `setup_repo.py` during [demo setup](setup/demo.md), or you can create it manually.

## Labels

| Label | Purpose |
|---|---|
| **Plan Approved** | Added to an issue to trigger Smithy's implementation phase |

