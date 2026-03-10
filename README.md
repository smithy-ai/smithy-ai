# Smithy-AI

An orchestrator for AI-assisted software development. Managing planning, building, and review with autonomous Claude Code agents.

> **This project is a work in progress.** Feel free to watch the repo or open a discussion if you're interested.

## Overview

Smithy-AI coordinates multiple AI agents working alongside human developers through a Gitlab or Forgejo-based workflow:

- **Agent Smithy**: plans and implements features based on issues, creates pull requests, and responds to review feedback.
- **The Architect**: reviews pull requests against established best practices and maintains the project's knowledge base in a separate context repository.

### Smithy development workflow

1. Create an issue and assign to Agent Smithy
2. Smithy creates a branch, let's Claude Code write a plan and shares it with you
3. You add comments to the issue to improve the plan, once done label the issue "Plan Approved"
4. Smithy creates a draft pull-request, implements the plan, and watches CI status to validate
5. You review the issue manually or request a PR review by The Architect
6. Smithy responds to your (and The Architect's) review comments
7. You remove the Draft status of the PR, Smithy cleans up his plan and related file. Done.

### The Architect learning workflow


1. Once a PR is merged or rejected, The Architect scans the (human) review comments
2. If the knowledge base (context) requires updating based on the comments, The Architect opens a PR on the context repository
3. This PR, just like the build flow, allows you to review and request changes


## Demo setup

The `demo/` directory contains a Docker Compose stack that runs a local Forgejo instance with the orchestrator.

```bash
cd demo
cp .env.example .env
# Edit .env with your CLAUDE_CODE_OAUTH_TOKEN (from `claude setup-token`)

docker compose up -d

# Once Forgejo is running, set up bot users and tokens:
python3 scripts/setup_instance.py

# Configure a repository for use with the orchestrator:
python3 scripts/setup_repo.py owner/repo
```

## License

This project is licensed under [AGPL-3.0](LICENSE). See [CONTRIBUTING.md](CONTRIBUTING.md) for contribution terms.
