# Configuration Reference

The orchestrator is configured by one YAML file. It loads the path in
`ORCHESTRATOR_CONFIG`, then `/config/orchestrator.yml`, then the classpath example.
The environment is used only when a `SecretRef` names an environment variable.

```yaml
apiVersion: smithy.ai/v1alpha1
kind: OrchestratorConfig

storage:
  database: /data/smithy.db
  metrics: /data/metrics.jsonl

runtime:
  docker:
    command: docker
    network: smithy-net
    taskImage: ghcr.io/smithy-ai/claude-task-default:dev
    caches: [pnpm, npm, maven, gradle]

agent:
  claude:
    model: claude-opus-5
    oauthToken: {env: CLAUDE_CODE_OAUTH_TOKEN}
    apiKey: {env: ANTHROPIC_API_KEY}
    turnTimeout: 60m          # budget for one agent turn; overrunning turns are killed
    takeoverTimeout: 5m       # budget for a turn a human drove from the dashboard

auth:
  admin:
    passwordHash: {env: ADMIN_PASSWORD_HASH}

connectors:
  forgejo-main:
    provider: forgejo
    url: http://forgejo:3000
    externalUrl: https://git.example.com
    webhookSecret: {env: FORGEJO_WEBHOOK_SECRET}
    actors:
      smithy:
        username: smithy-bot
        token: {env: SMITHY_FORGEJO_TOKEN}
        git:
          name: Smithy
          email: smithy@example.com
      architect:
        username: architect-bot
        token: {file: /run/secrets/architect_forgejo_token}
        git:
          name: Architect
          email: architect@example.com

defaults:
  vcs: forgejo-main
  issueTracker: event.source
  actor: smithy

workflows:
  definitionsDir: /config/workflows
  repositoryWorkflows: true
  defaults:
    branchPrefix: smithy/
    planApprovedLabel: Plan Approved

ci: {autofix: false}
knowledgebase:
  enabled: false
  url: http://knowledgebase:8000/mcp
  toolName: searchKnowledge
```

Unknown fields and invalid connector references fail startup. A deployment should
mount the file read-only and mount writable storage separately:

```yaml
volumes:
  - ./config/orchestrator.yml:/config/orchestrator.yml:ro
  - ./config/workflows:/config/workflows:ro
  - orchestrator-data:/data
```

## Agent turn timeout

`agent.claude.turnTimeout` caps the wall-clock time one agent turn may take —
`45m`, `2h`, `900s` and `PT45M` are all accepted, and the built-in default is
60 minutes. The deadline is enforced inside the task container, so an overrunning
turn is actually killed rather than left running after the orchestrator stops
waiting for it.

A turn that hits the cap fails its step with:

```
Claude turn on <container> (session=<id>) exceeded its 60m budget and was killed.
```

Raise the value for workflows whose build stage legitimately runs longer, or split
the step into smaller turns. Note that the budget is per turn, not per run: a
workflow with five turns can run for five times this long.

`agent.claude.takeoverTimeout` (default 5 minutes) is the budget for a turn a
person drove from the dashboard. It is deliberately much shorter: someone is
waiting on that reply in a browser, so an unanswered request is a hung dashboard
rather than a long job.

A run's turns all share one agent session, and that session cannot take two
concurrent processes. Taking control does not interrupt a turn that is already
running — the lease stops new events being dispatched, not work in flight — so a
message sent mid-turn is refused with a 409 and

```
The agent on run <id> is in the middle of a turn.
```

rather than queued behind it. Wait for the turn to land, or stop the run.

## Secrets

Every credential accepts exactly one secret source:

```yaml
token: {env: SMITHY_GITHUB_TOKEN}
token: {file: /run/secrets/smithy_github_token}
token: {literal: local-development-only}
```

Environment and file references keep values out of the deployment file. Literal
values are intended only for local development. Resolved values are never included
in configuration diagnostics.

## Connectors and actors

A connector ID such as `github-main` is the stable routing identity. `provider`
selects the implementation (`forgejo`, `gitlab`, `github`, or `jira`). Webhooks use
the connector ID:

```text
https://smithy.example.com/webhooks/github-main
```

Actor identities are connector-specific and never inherit another actor's
credentials. Every workflow actor must be configured on each connector it acts
through; a missing identity fails the action instead of posting as the default
actor.

Actors are logical identities scoped to a connector. Workflows refer to `smithy`,
`architect`, or another logical name; the connector resolves it to a username,
Jira account ID, credentials, and git identity. An actor omitted from a connector
uses the default actor's credentials for outbound actions and is not recognized as
an inbound assignee on that connector.

GitLab connectors may set `tokenType: oauth2` (the default) or
`tokenType: private-token`.

## Jira with a VCS connector

Jira is an issue connector, never a VCS connector. A split deployment defines both:

```yaml
connectors:
  gitlab-main:
    provider: gitlab
    url: https://gitlab.example.com
    webhookSecret: {env: GITLAB_WEBHOOK_SECRET}
    actors:
      smithy:
        username: smithy-bot
        token: {env: SMITHY_GITLAB_TOKEN}
        git: {name: Smithy, email: smithy@example.com}

  jira-product:
    provider: jira
    url: https://company.atlassian.net
    webhookSecret: {env: JIRA_WEBHOOK_SECRET}
    actors:
      smithy:
        accountId: abc123
        email: smithy@example.com
        apiToken: {env: SMITHY_JIRA_API_TOKEN}
    issueMapping:
      repositoryField: customfield_12345
      allowStoriesWithoutRepository: true
      planApprovedLabel: plan-approved
      planApprovedStatus: Ready for Smithy

defaults:
  vcs: gitlab-main
  issueTracker: event.source
  actor: smithy
```

Issue actions answer the connector that produced the event. Repository actions use
that source when it is a VCS connector and otherwise use `defaults.vcs`.

## Designs

A design usually settles what a ticket leaves open, and it reaches the ticket as a
Figma link rather than a file — which an agent in a container cannot open. Give
the orchestrator a Figma token and it renders every design a ticket links to and
copies the images into the container, where the prompt names them by path
alongside the attachments:

```yaml
figma:
  enabled: true
  token: {env: FIGMA_TOKEN}
  format: png     # png, jpg, svg or pdf
  scale: 2        # 0.01-4; 2 keeps design text legible
  maxDesigns: 10  # frames one ticket may pull in
```

The token is a Figma personal access token with read access to file content
(`file_content:read`; a legacy unscoped token works too). Access is per-account:
the token's owner must be able to open the files your tickets link to, so a
design in a team file needs an account with access to that team.

Links are read from the issue description, its comments, and — on Jira — its
remote links, which is what "Link issue → Add web link" writes. A link that names
a frame (`?node-id=...`) renders that frame; a link to a file renders the
top-level frames of its first page, up to `maxDesigns`.

!!! warning "Jira's Designs panel is not readable"

    A design added through the Figma for Jira app's **Designs** panel is stored
    by that app, not by Jira: it is not a remote link, not an attachment, and no
    public Jira REST API returns it. Nothing here can see it. For a design to be
    picked up, its URL has to appear in the issue description, in a comment, or
    as a web link — pasting the Figma URL into the description is enough, and the
    Figma app still shows it in the Designs panel as well.

Off by default. While it is off, nothing about a ticket is sent to Figma and
`attachments.fetch` behaves exactly as it did before. A design that will not
render is logged and skipped rather than failing the run.

## Repository catalogs

Reusable coordinator catalogs belong in deployment configuration:

```yaml
repositoryCatalogs:
  acme-product:
    - {source: gitlab-main, owner: acme, repo: api, description: HTTP API}
    - {source: gitlab-main, owner: acme, repo: web, description: Web client}
```

Workflow customization remains in `/config/workflows`, not in the main config. A
small workflow file can extend the built-in coordinator and import a catalog:

```yaml
apiVersion: smithy.ai/v1alpha1
kind: Workflow
metadata:
  name: acme-coordinator
  extends: feature-coordinator
vars:
  repositoryCatalog: acme-product
  storyRepos: [PRODUCT/PRODUCT]
```

The registry resolves `repositoryCatalog` into the workflow's `vars.catalog` and
rejects unknown catalog names.

## Repository-local configuration

Repository behavior stays with the repository. `.smithy/config.yml` currently
supports the context repository:

```yaml
context:
  repository: shared-guidelines
```

Repository-owned workflows live under `.smithy/workflows/*.yml` and can be disabled
deployment-wide with `workflows.repositoryWorkflows: false`.
