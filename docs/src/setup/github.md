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
repository.

Every repository the orchestrator may open a pull request in needs the webhook.
When the bot token can read a repository's webhooks (admin on the repository),
`pr.create` checks for one on this connector's path after opening a pull request
and posts a heads-up on it when comments there could not reach the orchestrator.
