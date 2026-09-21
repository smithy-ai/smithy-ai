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

Every project the orchestrator may open a merge request in needs this webhook,
including each entry of a repository catalog a coordinator fans out to. A merge
request in a project without one is work delivered somewhere the orchestrator
cannot hear: review comments there are never answered. GitLab CE has no group
webhooks, so register one per project. With `glab` authenticated as a maintainer:

```bash
ORCH=https://<orchestrator-host>/webhooks/gitlab-main
for p in group/repo-a group/repo-b; do
  glab api "projects/${p//\//%2F}/hooks" -X POST \
    -f url="$ORCH" -f token="$GITLAB_WEBHOOK_SECRET" \
    -F issues_events=true -F note_events=true -F merge_requests_events=true \
    -F push_events=true -F pipeline_events=true -F enable_ssl_verification=true
done
```

When the bot has Maintainer access, `pr.create` reads the project's webhooks
after opening a merge request and posts a heads-up on it if none delivers to
this connector, or if the one that does lacks comment or merge request events.
With Developer access GitLab hides the webhook list, and the check stays silent.
