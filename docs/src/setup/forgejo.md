# Connecting to an Existing Forgejo Instance

This guide covers connecting Smithy-AI to a Forgejo instance you already run, rather than using the bundled demo stack.

## Prerequisites

- Forgejo 7 or later
- A Docker host to run the orchestrator
- A Claude Code OAuth token — run `claude setup-token` to obtain one

## 1. Create bot users

Create two users in your Forgejo instance:

- **smithy** — the agent that plans and implements code
- **architect** — the agent that reviews PRs and maintains the knowledge base

You can use custom usernames by setting `SMITHY_BOT_USER` and `ARCHITECT_BOT_USER` in your environment.

## 2. Generate API tokens

For each bot user, generate an API token with full scope:

1. Log in as the bot user
2. Go to **Settings → Applications → Generate New Token**
3. Grant all permissions
4. Save the tokens — you'll need them for the orchestrator configuration

## 3. Configure environment

Set the following environment variables for the orchestrator:

```bash
FORGEJO_URL=http://your-forgejo:3000        # Internal URL (reachable from orchestrator)
FORGEJO_EXTERNAL_URL=https://forgejo.example.com  # Browser-reachable URL
SMITHY_FORGEJO_TOKEN=<smithy token>
ARCHITECT_FORGEJO_TOKEN=<architect token>
WEBHOOK_SECRET=<a random secret string>
CLAUDE_CODE_OAUTH_TOKEN=<your oauth token>
```

See the [configuration reference](../configuration.md) for all available settings.

## 4. Run the orchestrator

Use `demo/docker-compose.yml` as a starting template. At minimum, the orchestrator service needs:

- `/var/run/docker.sock` mounted (to spawn task containers)
- The environment variables above
- Network connectivity to your Forgejo instance

```yaml
services:
  orchestrator:
    image: ghcr.io/smithy-ai/orchestrator:latest
    ports:
      - "8080:8080"
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
    environment:
      - FORGEJO_URL=${FORGEJO_URL}
      - FORGEJO_EXTERNAL_URL=${FORGEJO_EXTERNAL_URL}
      - SMITHY_FORGEJO_TOKEN=${SMITHY_FORGEJO_TOKEN}
      - ARCHITECT_FORGEJO_TOKEN=${ARCHITECT_FORGEJO_TOKEN}
      - WEBHOOK_SECRET=${WEBHOOK_SECRET}
      - CLAUDE_CODE_OAUTH_TOKEN=${CLAUDE_CODE_OAUTH_TOKEN}
      - DOCKER_NETWORK=${DOCKER_NETWORK:-forgejo-net}
      - TASK_IMAGE=${TASK_IMAGE:-claude-task:latest}
```

## 5. Per-repository setup

For each repository you want Smithy to work on:

1. **Add bot collaborators**: Add both `smithy` and `architect` users as collaborators with write access
2. **Create webhook**: Add a webhook pointing to `http://<orchestrator-host>:8080/webhooks/forgejo` with the secret you configured
3. **Create label**: Add a "Plan Approved" label — this triggers Smithy's implementation phase

!!! tip
    If your Forgejo instance is API-accessible from your machine, you can use `setup_repo.py` to automate per-repo setup:
    ```bash
    python3 demo/scripts/setup_repo.py owner/repo
    ```
