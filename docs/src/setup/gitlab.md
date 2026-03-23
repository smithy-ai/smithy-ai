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

For each bot user, generate a personal access token with `api` scope:

1. Log in as the bot user
2. Go to **Preferences → Access Tokens**
3. Create a token with the `api` scope
4. Save the tokens

## 3. Configure environment

Set the following environment variables:

```bash
VCS_PROVIDER=gitlab
GITLAB_URL=http://your-gitlab:80              # Internal URL (reachable from orchestrator)
GITLAB_EXTERNAL_URL=https://gitlab.example.com  # Browser-reachable URL
SMITHY_GITLAB_TOKEN=<smithy token>
ARCHITECT_GITLAB_TOKEN=<architect token>
GITLAB_WEBHOOK_SECRET=<a random secret string>
CLAUDE_CODE_OAUTH_TOKEN=<your oauth token>
```

See the [configuration reference](../configuration.md) for all available settings.

## 4. Run the orchestrator

Use `local-gitlab/docker-compose.yml` as a starting template:

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
      - GITLAB_WEBHOOK_SECRET=${GITLAB_WEBHOOK_SECRET}
      - CLAUDE_CODE_OAUTH_TOKEN=${CLAUDE_CODE_OAUTH_TOKEN}
      - DOCKER_NETWORK=${DOCKER_NETWORK:-gitlab-net}
      - TASK_IMAGE=${TASK_IMAGE:-claude-task-default:latest}
```

## 5. Per-repository setup

For each repository you want Smithy to work on:

1. **Add bot members**: Add both `smithy` and `architect` as project members with Developer role or higher
2. **Create webhook**: Go to **Settings → Webhooks** and add a webhook pointing to `http://<orchestrator-host>:8080/webhooks/gitlab` with your secret

