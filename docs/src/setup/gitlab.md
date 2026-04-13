# Connecting to a GitLab Instance

This guide covers connecting Smithy-AI to a GitLab instance (self-hosted or gitlab.com).

## Prerequisites

- A GitLab instance (self-hosted or gitlab.com)
- A Docker host to run the orchestrator
- A Claude Code OAuth token — run `claude setup-token` to obtain one

## 1. Create bot users

Create two users in GitLab:

- **smithy** — the agent that plans and implements code
- **architect** — the agent that reviews PRs and maintains the knowledge base

You can use custom usernames by setting `SMITHY_BOT_USER` and `ARCHITECT_BOT_USER`.

## 2. Generate access tokens

For each bot user, generate an access token with the `api`, `read_repository`, and `write_repository` scopes. Smithy supports two types of GitLab tokens:

**Option A: OAuth2 tokens** (group or project access tokens)

1. Go to the group or project **Settings → Access Tokens**
2. Create a token with `api`, `read_repository`, and `write_repository` scopes
3. Set `GITLAB_TOKEN_TYPE=oauth2` in your environment

**Option B: Personal or impersonation tokens**

1. **Personal access token**: Log in as the bot user → **Preferences → Access Tokens** → create a token with `api`, `read_repository`, and `write_repository` scopes
2. **Impersonation token**: As an admin → **Admin → Users → (bot user) → Impersonation Tokens** → create a token with the same scopes
3. Set `GITLAB_TOKEN_TYPE=private-token` in your environment

## 3. Configure environment

Set the following environment variables:

```bash
VCS_PROVIDER=gitlab
GITLAB_URL=http://your-gitlab:80              # Internal URL (reachable from orchestrator)
GITLAB_EXTERNAL_URL=https://gitlab.example.com  # Browser-reachable URL
GITLAB_TOKEN_TYPE=oauth2                        # or "private-token" for PAT/impersonation tokens
SMITHY_GITLAB_TOKEN=<smithy token>
ARCHITECT_GITLAB_TOKEN=<architect token>
GITLAB_WEBHOOK_SECRET=<a random secret string>
CLAUDE_CODE_OAUTH_TOKEN=<your oauth token>
```

See the [configuration reference](../configuration.md) for all available settings.

## 4. Create the Docker network

The orchestrator spawns task containers that need network connectivity to your GitLab instance. Create a shared Docker network for them:

```bash
docker network create smithy-net
```

## 5. Run the orchestrator

Use `examples/full/docker-compose.yml` as a starting template, or create a minimal compose file:

```yaml
services:
  orchestrator:
    image: ghcr.io/smithy-ai/orchestrator:latest
    ports:
      - "8080:8080"
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
    environment:
      - VCS_PROVIDER=gitlab
      - GITLAB_URL=${GITLAB_URL}
      - GITLAB_EXTERNAL_URL=${GITLAB_EXTERNAL_URL}
      - SMITHY_GITLAB_TOKEN=${SMITHY_GITLAB_TOKEN}
      - ARCHITECT_GITLAB_TOKEN=${ARCHITECT_GITLAB_TOKEN}
      - GITLAB_TOKEN_TYPE=${GITLAB_TOKEN_TYPE:-oauth2}
      - GITLAB_WEBHOOK_SECRET=${GITLAB_WEBHOOK_SECRET}
      - CLAUDE_CODE_OAUTH_TOKEN=${CLAUDE_CODE_OAUTH_TOKEN}
      - DOCKER_NETWORK=${DOCKER_NETWORK:-smithy-net}
      - TASK_IMAGE=${TASK_IMAGE:-claude-task-default:latest}
```

## 6. Per-repository setup

For each repository you want Smithy to work on:

1. **Add bot members**: Add both `smithy` and `architect` as project members with Developer role or higher
2. **Create webhook**: Go to **Settings → Webhooks** and add a webhook pointing to `http://<orchestrator-host>:8080/webhooks/gitlab` with your secret

