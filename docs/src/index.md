# Smithy-AI

Smithy-AI runs coding agents against your repositories, driven by what happens in
your issue tracker.

Assign an issue to the bot and an agent picks it up: it reads the repository,
writes a plan, waits for you to approve it, implements the work, opens a pull
request, and answers review comments on it. Each agent works in its own Docker
container with a real checkout, so it can build and test what it writes.

## What makes it different

The interesting part is not that it runs an agent. It is that **what the agent
does is a file you can read**.

A workflow is a YAML definition: which events it cares about, what states a piece
of work moves through, and what happens on each transition. The engine executes
it. Nothing in the platform knows what a "plan" is, or a review round, or a
feature — those words only appear in workflow files.

```yaml
new:
  on:
    issue.assigned:
      to: refine
      steps:
        - uses: container.init      # clone the repository
        - uses: agent.run           # let the agent plan
        - uses: issue.comment       # post the plan back
        - uses: gate.await          # wait for a human
```

Four things follow from that:

- **You can change how the agents behave** without writing Java — edit a file, or
  drop your own into `/config/workflows`, or commit one to `.smithy/workflows/`
  in a repository so it travels with the code it governs.
- **Work is durable.** A run is a row in a database, not a container. Delete the
  container and the run still exists, with its history. Restart the orchestrator
  mid-agent-turn and it resumes at the step it reached.
- **Workflows can coordinate other workflows.** One can plan a feature across
  several repositories, create an issue in each, spawn a run to work each one,
  and release them in dependency order.
- **It is provider-agnostic in a real sense.** Forgejo, GitLab, GitHub and Jira
  are connectors. A feature story tracked in Jira can fan out into GitLab issues,
  because parentage lives in Smithy's own store rather than in any tracker.

## What ships with it

Four workflows, all definitions, all replaceable:

| Workflow | What it does |
|---|---|
| `smithy-development` | One issue, one repository: plan, approve, implement, open a pull request, answer review |
| `architect-review` | Reviews a pull request against the guidelines a repository points at |
| `architect-learn` | Turns what a merged pull request argued about into a proposed guideline change |
| `feature-coordinator` | Splits a feature across repositories and drives the children in dependency order |

## Where to go next

- **[Concepts](concepts.md)** — the model: events, runs, actions, connectors,
  actors. Read this first; everything else assumes it.
- **[Writing a workflow](workflows/index.md)** — build one from scratch.
- **[Workflow reference](workflows/reference.md)** — every field, every action,
  every event. Complete and mechanical; this is what to hand an LLM that is
  writing a workflow for you.
- **[Coordinating across repositories](workflows/coordinator.md)** — the
  multi-repo case.
- **[Setup](setup/demo.md)** — get one running.
- **[Day to day](usage.md)** — what you actually do once it is running.

!!! note "Status"

    This project is a work in progress. The workflow engine and the four built-in
    definitions are in place and tested, including end to end against live
    providers, but interfaces are still moving.
