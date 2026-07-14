# Configuration Reference

Smithy-AI is configured through environment variables. These map to settings in `orchestrator.yml`. All variables can be set in your `.env` file or passed directly as environment variables to the orchestrator container.

## Docker settings

| Variable | Default | Description |
|---|---|---|
| `DOCKER_COMMAND` | `docker` | Docker CLI command |
| `DOCKER_NETWORK` | `smithy-net` | Docker network that task containers attach to |
| `TASK_IMAGE` | `claude-task:latest` | Docker image used for task containers |
| `CACHE_VOLUMES` | `pnpm,npm` | Comma-separated cache volume types: `pnpm`, `npm`, `maven`, `gradle` |

## Claude settings

| Variable | Default | Description |
|---|---|---|
| `CLAUDE_CODE_OAUTH_TOKEN` | — | OAuth token from `claude setup-token` **(required)** |

## VCS provider

| Variable | Default | Description |
|---|---|---|
| `VCS_PROVIDER` | `forgejo` | Git provider: `forgejo`, `gitlab`, or `github` |
| `ISSUE_PROVIDER` | — | Override issue provider (defaults to `VCS_PROVIDER` value) |

## Forgejo settings

| Variable | Default | Description |
|---|---|---|
| `FORGEJO_URL` | `http://forgejo:3000` | Internal Forgejo URL (reachable from orchestrator) |
| `FORGEJO_EXTERNAL_URL` | `http://localhost:3000` | Browser-reachable Forgejo URL |
| `WEBHOOK_SECRET` | — | HMAC secret for verifying Forgejo webhook signatures |
| `SMITHY_FORGEJO_TOKEN` | — | API token for the smithy bot user |
| `ARCHITECT_FORGEJO_TOKEN` | — | API token for the architect bot user |

## GitHub settings

| Variable | Default | Description |
|---|---|---|
| `GITHUB_URL` | — | GitHub instance URL. Leave empty for github.com; set for GitHub Enterprise (e.g. `https://github.example.com`) |
| `GITHUB_EXTERNAL_URL` | — | Browser-reachable URL (defaults to `GITHUB_URL` or `https://github.com`) |
| `GITHUB_WEBHOOK_SECRET` | — | HMAC secret for verifying GitHub webhook signatures |
| `SMITHY_GITHUB_TOKEN` | — | Personal access token for the smithy bot user |
| `ARCHITECT_GITHUB_TOKEN` | — | Personal access token for the architect bot user |

## GitLab settings

| Variable | Default | Description |
|---|---|---|
| `GITLAB_URL` | — | Internal GitLab URL (reachable from orchestrator) |
| `GITLAB_EXTERNAL_URL` | — | Browser-reachable GitLab URL |
| `GITLAB_TOKEN_TYPE` | `oauth2` | Token type: `oauth2` for group/project access tokens, `private-token` for personal or impersonation tokens |
| `GITLAB_WEBHOOK_SECRET` | — | Secret for verifying GitLab webhook signatures |
| `SMITHY_GITLAB_TOKEN` | — | Access token for the smithy bot user |
| `ARCHITECT_GITLAB_TOKEN` | — | Access token for the architect bot user |

## Bot settings

| Variable | Default | Description |
|---|---|---|
| `SMITHY_BOT_USER` | `smithy` | Username of the smithy bot |
| `SMITHY_BOT_EMAIL` | `smithy@localhost` | Email address of the smithy bot (used for git commits and push detection) |
| `ARCHITECT_BOT_USER` | `architect` | Username of the architect bot |
| `ARCHITECT_BOT_EMAIL` | `architect@localhost` | Email address of the architect bot (used for git commits) |

