# Coding agents, driven by your issue tracker

Smithy-AI runs Claude Code sessions in isolated Docker containers, triggered by
what happens in GitHub, GitLab, Forgejo or Jira. Assign an issue to the bot and an
agent plans it, waits for your approval, implements it, opens a pull request, and
answers review comments.

> **This project is a work in progress.** Feel free to watch the repo or open a
> discussion if you're interested.

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
repository. A definition with the same name as an earlier one replaces it.

One execution of a workflow is a **run**. Runs live in the orchestrator's
database: a run keeps its history once its container is gone, and picks up at the
step it reached if the orchestrator restarts.

Workflows can start and wait on other workflows, which is how a feature is split
across repositories and its parts released in dependency order.

## What ships with it

| Workflow | What it does |
|---|---|
| `smithy-development` | One issue, one repository: plan, approve, implement, pull request, review |
| `architect-review` | Reviews a pull request against the guidelines a repository points at |
| `architect-learn` | Turns what a merged pull request argued about into a proposed guideline change |
| `feature-coordinator` | Splits a feature across repositories, drives children in dependency order |

Each is a definition you can override or replace.

![Diagram of Smithy workflow](docs/src/assets/Smithy-diagram.png)

Human actions are in yellow. The project knowledge base is an optional separate
repository of markdown files used as best practices and preferences.

### The development workflow, step by step

1. Create an issue and assign it to Agent Smithy
2. Smithy creates a branch, has Claude Code write a plan, and shares it with you
3. Comment on the issue to improve the plan; when happy, label it "Plan Approved"
4. Smithy opens a draft pull request, implements the plan, and watches CI
5. Review it yourself, or request a review from The Architect
6. Smithy responds to review comments
7. Remove the draft status; Smithy cleans up its planning artifacts. Done.

### The Architect learning workflow (optional)

1. Once a PR is merged or rejected, The Architect scans the human review comments
2. If the knowledge base needs updating, The Architect opens a PR on the context repository
3. That PR, like any other, is yours to review or reject

## Demo setup

The `examples/demo/` directory contains a Docker Compose stack that runs a local Forgejo instance with the orchestrator.

```bash
cd examples/demo
cp .env.example .env
```

Edit .env with your CLAUDE_CODE_OAUTH_TOKEN (from `claude setup-token`)
Other fields are set automatically by the setup scripts below.

```bash
# Start the docker compose demo stack
docker compose up -d
```

Once Forgejo is running, configure it on [http://localhost:3000](http://localhost:3000) and create a repository.
Then run the scripts to configure Forgejo and the repository:

```bash
# Only the first time:
python3 scripts/setup_instance.py

# For every repository:
python3 scripts/setup_repo.py owner/repo
```

## Documentation

Start with [Concepts](https://smithy-ai.github.io/smithy-ai/concepts/): events,
runs, actions, connectors and actors. The other pages assume it.

- **Setup**: [Demo](https://smithy-ai.github.io/smithy-ai/setup/demo/) · [GitHub](https://smithy-ai.github.io/smithy-ai/setup/github/) · [GitLab](https://smithy-ai.github.io/smithy-ai/setup/gitlab/) · [Forgejo](https://smithy-ai.github.io/smithy-ai/setup/forgejo/)
- **Usage**: [Day to day](https://smithy-ai.github.io/smithy-ai/usage/) · [Configuration reference](https://smithy-ai.github.io/smithy-ai/configuration/)
- **Workflows**: [Writing one](https://smithy-ai.github.io/smithy-ai/workflows/) · [Reference](https://smithy-ai.github.io/smithy-ai/workflows/reference/) · [Across repositories](https://smithy-ai.github.io/smithy-ai/workflows/coordinator/)
- [Custom task images](https://smithy-ai.github.io/smithy-ai/advanced/custom-task-images/)



## License

This project is licensed under [AGPL-3.0](LICENSE). See [CONTRIBUTING.md](CONTRIBUTING.md) for contribution terms.
