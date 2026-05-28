# Connecting to GitHub

This guide covers connecting Smithy-AI to GitHub (github.com or GitHub Enterprise).

## Prerequisites

- A GitHub organization or personal account
- A Docker host to run the orchestrator (reachable from the internet so GitHub can deliver webhooks)
- A Claude Code OAuth token — run `claude setup-token` to obtain one

## 1. Create bot accounts

Create two GitHub accounts (or use machine/service accounts within your organization):

- **smithy** — the agent that plans and implements code
- **architect** — the agent that reviews PRs and maintains the knowledge base

You can use custom usernames by setting `SMITHY_BOT_USER` and `ARCHITECT_BOT_USER`.

## 2. Generate personal access tokens

For each bot account, generate a **classic personal access token** with the following scopes:

- `repo` — full repository access (read/write code, issues, pull requests)
- `read:org` — read organization membership (required if your repos are inside an organization)

Steps:

1. Log in as the bot account
2. Go to **Settings → Developer settings → Personal access tokens → Tokens (classic)**
3. Click **Generate new token (classic)**
4. Select the scopes above
5. Save the token — you will need it below

!!! tip "Fine-grained tokens"
    Fine-grained PATs work too. Grant **Read and Write** access for **Issues**, **Pull requests**, **Contents**, and **Metadata** on the target repositories.

## 3. Configure environment

Run the setup script to validate your tokens and generate a webhook secret:

```bash
python3 scripts/github/setup.py
```

This will write `SMITHY_GITHUB_TOKEN`, `ARCHITECT_GITHUB_TOKEN`, `GITHUB_WEBHOOK_SECRET`, and the bot usernames to your `.env` file.

Alternatively, set the following environment variables manually:

```bash
VCS_PROVIDER=github
SMITHY_GITHUB_TOKEN=<smithy token>
ARCHITECT_GITHUB_TOKEN=<architect token>
GITHUB_WEBHOOK_SECRET=<a random secret string>
CLAUDE_CODE_OAUTH_TOKEN=<your oauth token>
```

For **GitHub Enterprise**, also set:

```bash
GITHUB_URL=https://github.example.com         # Internal URL (reachable from orchestrator)
GITHUB_EXTERNAL_URL=https://github.example.com  # Browser-reachable URL
```

See the [configuration reference](../configuration.md) for all available settings.

## 4. Create the Docker network

The orchestrator spawns task containers that need outbound network access to reach GitHub. Create a shared Docker network:

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
      - VCS_PROVIDER=github
      - SMITHY_GITHUB_TOKEN=${SMITHY_GITHUB_TOKEN}
      - ARCHITECT_GITHUB_TOKEN=${ARCHITECT_GITHUB_TOKEN}
      - GITHUB_WEBHOOK_SECRET=${GITHUB_WEBHOOK_SECRET}
      - CLAUDE_CODE_OAUTH_TOKEN=${CLAUDE_CODE_OAUTH_TOKEN}
      - DOCKER_NETWORK=${DOCKER_NETWORK:-smithy-net}
      - TASK_IMAGE=${TASK_IMAGE:-claude-task-default:latest}
    networks:
      - smithy-net

networks:
  smithy-net:
    name: smithy-net
```

## 6. Per-repository setup

!!! tip "Automated setup"
    If the orchestrator is reachable and your tokens are configured, you can automate all per-repo steps with the setup script:
    ```bash
    python3 scripts/github/setup_repo.py owner/repo --orchestrator-url https://smithy.example.com
    ```

For each repository you want Smithy to work on:

### Add collaborators

Add both `smithy` and `architect` as repository collaborators with **Write** access (or **Triage** for the architect if it only needs to comment):

1. Go to **Settings → Collaborators and teams**
2. Click **Add people** and invite each bot account with Write access

### Create the webhook

1. Go to **Settings → Webhooks → Add webhook**
2. Set **Payload URL** to `https://<your-orchestrator-host>/webhooks/github`
3. Set **Content type** to `application/json`
4. Set **Secret** to the value of `GITHUB_WEBHOOK_SECRET`
5. Select **Let me select individual events** and enable:
   - Issues
   - Issue comments
   - Pull requests
   - Pull request reviews
   - Pull request review comments
   - Pushes
   - Workflow runs
6. Click **Add webhook**

### Create the label

Create a **"Plan Approved"** label in the repository — this triggers Smithy's implementation phase after you approve a plan:

1. Go to **Issues → Labels → New label**
2. Name it exactly `Plan Approved`

!!! note "Draft pull requests"
    Smithy creates pull requests as **drafts** on GitHub (native draft support). When you are ready for implementation to finish, mark the PR as **"Ready for review"** rather than removing a "WIP:" prefix as you would with Forgejo.
