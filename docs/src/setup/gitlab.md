# GitLab Setup

Create bot users or access tokens for the logical actors you enable and give them
Developer access to the projects. Configure a named GitLab connector:

```yaml
connectors:
  gitlab-main:
    provider: gitlab
    url: http://gitlab.internal
    externalUrl: https://gitlab.example.com
    tokenType: oauth2
    webhookSecret: {env: GITLAB_WEBHOOK_SECRET}
    actors:
      smithy:
        username: smithy-bot
        token: {env: SMITHY_GITLAB_TOKEN}
        git: {name: Smithy, email: smithy@example.com}
      architect:
        username: architect-bot
        token: {env: ARCHITECT_GITLAB_TOKEN}
        git: {name: Architect, email: architect@example.com}
defaults:
  vcs: gitlab-main
  issueTracker: event.source
  actor: smithy
```

Use `tokenType: private-token` for personal or impersonation tokens. The default,
`oauth2`, is appropriate for group and project access tokens.

Add a project webhook pointing to:

```text
https://<orchestrator-host>/webhooks/gitlab-main
```

Enable issue, comment, push, merge request, and pipeline events. Use the configured
webhook secret as GitLab's secret token.
