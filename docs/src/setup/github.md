# GitHub Setup

Create machine users or fine-grained tokens for the actors you enable and add the
accounts as repository collaborators. Configure a named connector:

```yaml
connectors:
  github-main:
    provider: github
    url: https://github.com
    externalUrl: https://github.com
    webhookSecret: {env: GITHUB_WEBHOOK_SECRET}
    actors:
      smithy:
        username: smithy-bot
        token: {env: SMITHY_GITHUB_TOKEN}
        git:
          name: Smithy
          email: smithy@users.noreply.github.com
      architect:
        username: architect-bot
        token: {env: ARCHITECT_GITHUB_TOKEN}
        git:
          name: Architect
          email: architect@users.noreply.github.com
defaults:
  vcs: github-main
  issueTracker: event.source
  actor: smithy
```

For GitHub Enterprise, set `url` to the instance root; the API path is derived by
the connector. Add a JSON webhook with the configured secret and this payload URL:

```text
https://<orchestrator-host>/webhooks/github-main
```

Enable issues, issue comments, pushes, pull requests, pull request reviews, review
comments, and workflow runs. Configure the same connector webhook on the context
repository. An organization webhook covers every repository in it and is the
easiest way to cover a catalog.

Every repository the orchestrator may open a pull request in must deliver to
this webhook, through its own hook or the organization's. After opening a pull
request, `pr.create` waits a minute for GitHub to report the opening; if nothing
arrives, it posts a heads-up on the pull request, flags the run on the dashboard,
and reports `webhookMissing`.
