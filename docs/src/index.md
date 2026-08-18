# Smithy-AI

Smithy-AI runs coding agents against your repositories, driven by what happens in
your issue tracker.

Assign an issue to the bot and an agent picks it up: it reads the repository,
writes a plan, waits for you to approve it, implements the work, opens a pull
request, and answers review comments on it. Each agent works in its own Docker
container with a real checkout, so it can build and test what it writes.

## Workflows

What an agent does is defined in YAML. A workflow says which events it responds
to, what states a piece of work moves through, and what happens on each
transition:

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

Some workflows ship with Smithy-AI. You add your own by mounting a directory at
`/config/workflows`, or by putting them in `.smithy/workflows/` inside a
repository. A definition with the same name as an earlier one replaces it, so you
can change how the agents behave without rebuilding anything.

One execution of a workflow is a **run**. Runs live in the orchestrator's
database: a run keeps its history once its container is gone, and picks up at the
step it reached if the orchestrator restarts mid-transition.

Workflows can start and wait on other workflows, which is how a feature is split
across several repositories and its parts released in dependency order.

Forgejo, GitLab, GitHub and Jira are all supported. A story tracked in one system
can produce work in another.

## What ships with it

| Workflow | What it does |
|---|---|
| `smithy-development` | One issue, one repository: plan, approve, implement, open a pull request, answer review |
| `architect-review` | Reviews a pull request against the guidelines a repository points at |
| `architect-learn` | Turns what a merged pull request argued about into a proposed guideline change |
| `feature-coordinator` | Splits a feature across repositories and drives the children in dependency order |

Each is a definition you can override or replace.

## Where to go next

- **[Concepts](concepts.md)**: events, runs, actions, connectors, actors. Start
  here; the other pages assume it.
- **[Setup](setup/demo.md)**: get one running.
- **[Day to day](usage.md)**: using it once it is running.
- **[Writing a workflow](workflows/index.md)**: build one from scratch.
- **[Workflow reference](workflows/reference.md)**: every field, action and event.
- **[Coordinating across repositories](workflows/coordinator.md)**: splitting a
  feature over several repositories.

!!! note "Status"

    This project is a work in progress. Interfaces are still moving.
