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
| `CLAUDE_CODE_OAUTH_TOKEN` | none | OAuth token from `claude setup-token` **(required)** |
| `CLAUDE_MODEL` | `opus` | Claude model used by agent sessions (e.g. `opus`, `sonnet`) |

## VCS provider

| Variable | Default | Description |
|---|---|---|
| `VCS_PROVIDER` | `forgejo` | Git provider: `forgejo`, `gitlab`, or `github` |
| `ISSUE_PROVIDER` | none | Override issue provider (defaults to `VCS_PROVIDER` value) |

## Forgejo settings

| Variable | Default | Description |
|---|---|---|
| `FORGEJO_URL` | `http://forgejo:3000` | Internal Forgejo URL (reachable from orchestrator) |
| `FORGEJO_EXTERNAL_URL` | `http://localhost:3000` | Browser-reachable Forgejo URL |
| `WEBHOOK_SECRET` | none | HMAC secret for verifying Forgejo webhook signatures |
| `SMITHY_FORGEJO_TOKEN` | none | API token for the smithy bot user |
| `ARCHITECT_FORGEJO_TOKEN` | none | API token for the architect bot user |
| `COORDINATOR_FORGEJO_TOKEN` | none | API token for the coordinator actor. Without it a coordinator acts as smithy, and a feature's plan appears written by the agent implementing it |

## GitHub settings

| Variable | Default | Description |
|---|---|---|
| `GITHUB_URL` | none | GitHub instance URL. Leave empty for github.com; set for GitHub Enterprise (e.g. `https://github.example.com`) |
| `GITHUB_EXTERNAL_URL` | none | Browser-reachable URL (defaults to `GITHUB_URL` or `https://github.com`) |
| `GITHUB_WEBHOOK_SECRET` | none | HMAC secret for verifying GitHub webhook signatures |
| `SMITHY_GITHUB_TOKEN` | none | Personal access token for the smithy bot user |
| `ARCHITECT_GITHUB_TOKEN` | none | Personal access token for the architect bot user |
| `COORDINATOR_GITHUB_TOKEN` | none | Personal access token for the coordinator actor |

## GitLab settings

| Variable | Default | Description |
|---|---|---|
| `GITLAB_URL` | none | Internal GitLab URL (reachable from orchestrator) |
| `GITLAB_EXTERNAL_URL` | none | Browser-reachable GitLab URL |
| `GITLAB_TOKEN_TYPE` | `oauth2` | Token type: `oauth2` for group/project access tokens, `private-token` for personal or impersonation tokens |
| `GITLAB_WEBHOOK_SECRET` | none | Secret for verifying GitLab webhook signatures |
| `SMITHY_GITLAB_TOKEN` | none | Access token for the smithy bot user |
| `ARCHITECT_GITLAB_TOKEN` | none | Access token for the architect bot user |
| `COORDINATOR_GITLAB_TOKEN` | none | Access token for the coordinator actor |

## Actors

An actor is a machine identity Smithy-AI acts as. Which actor an issue is
assigned to is how a person says what kind of work it is: a feature for the
coordinator, a task for smithy. They need separate accounts for that. See
[concepts](concepts.md#actors).

| Variable | Default | Description |
|---|---|---|
| `SMITHY_BOT_USER` | `smithy` | Username of the smithy actor |
| `SMITHY_BOT_EMAIL` | `smithy@localhost` | Email of the smithy actor (git commits, push detection) |
| `ARCHITECT_BOT_USER` | `architect` | Username of the architect actor |
| `ARCHITECT_BOT_EMAIL` | `architect@localhost` | Email of the architect actor (git commits) |
| `COORDINATOR_BOT_USER` | `coordinator` | Username of the coordinator actor, the one a feature story is assigned to |
| `COORDINATOR_BOT_EMAIL` | `coordinator@localhost` | Email of the coordinator actor |

An actor with no token of its own falls back to smithy's, so a single-account
deployment keeps working, at the cost of everything being attributed to that
account.

## Storage

| Variable | Default | Description |
|---|---|---|
| `DB_PATH` | `/config/smithy.db` | SQLite file holding runs, history and correlations. Mount it: this is the durable record of every piece of work |
| `METRICS_PATH` | none | Append-only metrics log |
| `ADMIN_PASSWORD_HASH` | none | bcrypt hash for the dashboard's `admin` user. A random password is generated and printed at startup when unset |


## Repository settings

Repositories can define Smithy-specific settings in `.smithy/config.yml`.

```yaml
context:
  repository: shared-guidelines
```

`context.repository` can be either a repository name in the same owner/group or an `owner/repo` value. When omitted, Smithy uses `<repo>-context`.

## Workflow settings

| Variable | Default | Description |
|---|---|---|
| `PLAN_APPROVED_LABEL` | `Plan Approved` | Label the adapters translate into `issue.plan_approved` |
| `SMITHY_BRANCH_PREFIX` | `smithy/` | Prefix the adapters recognise as an agent's branch |
| `WORKFLOW_DIR` | `/config/workflows` | Directory of workflow definitions. Files here override the built-ins by name |

Everything else about how the agents behave lives in the workflow definitions
themselves, not here. See [writing a workflow](workflows/index.md).

## Jira settings

Used when `ISSUE_PROVIDER=jira`, which lets stories be tracked in Jira while the
work happens in repositories.

| Variable | Default | Description |
|---|---|---|
| `JIRA_URL` | none | Jira instance URL |
| `JIRA_EMAIL` | none | Account email for API authentication |
| `JIRA_API_TOKEN` | none | API token |
| `JIRA_BOT_ACCOUNT_ID` | none | Account id assignment is detected against |
| `JIRA_WEBHOOK_SECRET` | none | Shared secret, sent as `X-Jira-Token` or `?token=` |
| `JIRA_REPO_FIELD` | none | Custom field holding `owner/repo[@base-branch]` |
| `JIRA_PLAN_APPROVED_LABEL` | `plan-approved` | Label that means approval |
| `JIRA_PLAN_APPROVED_STATUS` | none | Status transition that means approval |
| `JIRA_STORIES_WITHOUT_REPO` | `false` | Hand stories with no repository field to the workflows anyway, scoped to their Jira project. A coordinator picks repositories from its catalog and does not need the field; a development workflow does |
