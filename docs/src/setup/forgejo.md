# Forgejo Setup

Create separate Forgejo users for each actor you enable, add them as repository
collaborators, and create an API token for each account. Put connection details and
identities in `/config/orchestrator.yml`:

```yaml
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
        git: {name: Smithy, email: smithy@example.com}
      architect:
        username: architect-bot
        token: {env: ARCHITECT_FORGEJO_TOKEN}
        git: {name: Architect, email: architect@example.com}
defaults:
  vcs: forgejo-main
  issueTracker: event.source
  actor: smithy
```

Mount the file and pass the three referenced secrets. Configure each repository
webhook with this payload URL:

```text
https://<orchestrator-host>/webhooks/forgejo-main
```

Enable issue, issue comment, push, pull request, pull request comment, and Actions
run events. Use the same webhook on the context repository. The demo under
`examples/demo` automates user, token, label, collaborator, and webhook setup.
