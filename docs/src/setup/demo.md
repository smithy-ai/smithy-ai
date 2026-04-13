# Local Forgejo Demo

The `examples/demo/` directory contains a Docker Compose stack that runs a local Forgejo instance with the orchestrator. This is the fastest way to try Smithy-AI.

## Prerequisites

- Docker and Docker Compose
- Python 3
- A Claude Code OAuth token — run `claude setup-token` to obtain one

## 1. Configure environment

```bash
cd examples/demo
cp .env.example .env
```

Edit `.env` and set your `CLAUDE_CODE_OAUTH_TOKEN`. The remaining values are populated automatically by the setup scripts in the following steps.

## 2. Start the stack

```bash
docker compose up -d
```

This starts Forgejo, the orchestrator, and a Forgejo Actions runner.

## 3. Forgejo first-run setup

Open [http://localhost:3000](http://localhost:3000) in your browser and create an admin account. Then create a repository you want Smithy to work on.

## 4. Run setup scripts

The setup scripts configure bot users, tokens, webhooks, and labels.

### Instance setup (once per Forgejo instance)

```bash
python3 scripts/setup_instance.py
```

This creates the `smithy` and `architect` bot users, generates API tokens, a webhook secret, and a runner registration token. All values are written back to your `.env` file automatically.

### Repository setup (once per repository)

```bash
python3 scripts/setup_repo.py owner/repo
```

This adds the bot users as collaborators, creates the webhook pointing to the orchestrator, adds the "Plan Approved" label, and creates the context repository (`<repo>-context`).

## What's next

- Learn about the [workflow](../usage.md) to start using Smithy
- See the [configuration reference](../configuration.md) for all available settings
