# Contributing to Smithy-AI

Welcome! Thanks for considering contributing to Smithy-AI, feel free to open an issue or pull request.

## Communication

All project communication (issues, pull requests, discussions, code comments) should be in English.

## AI-Assisted Contributions

We welcome contributions that were written with the help of AI tools. However, every pull request must be submitted by a human who has reviewed the code, understands it, and is able to answer questions. Please do not submit AI-generated code that you haven't reviewed and understood yourself.

## Running the integration tests

Most of the build runs against in-process fakes. Two suites go further, and both
skip themselves when their prerequisites are absent, so `./gradlew :backend:build`
works anywhere.

`DockerLifecycleIT` needs Docker and an image with a repository baked into it, so
that nothing has to be served over the network. Build `claude-task-base`, then a
thin image on top containing a bare `/seed.git`, tagged `claude-task-seed:test`.

`LiveEndToEndIT` drives one issue through a real Forgejo, a real container and a
real Claude session. **It writes to the repository you point it at**: a branch, a
plan file, a comment, a pull request. Use a scratch repository, and one nothing
else is watching, since an orchestrator already subscribed to it will race the
test.

```bash
SMITHY_IT_URL=https://git.example.com \
SMITHY_IT_TOKEN=<forgejo token for the bot> \
SMITHY_IT_REPO=owner/scratch-repo \
SMITHY_IT_ISSUE=1 \
CLAUDE_CODE_OAUTH_TOKEN=<token> \
./gradlew :backend:test --tests '*LiveEndToEndIT*'
```

## Security

If you discover a security vulnerability that could put users at risk, please do not open a public issue. Instead, use GitHub's private security reporting feature to report it confidentially.

## Licensing

This project is licensed under AGPL-3.0. By submitting a pull request, you agree to license your contribution under AGPL-3.0 and additionally grant the project maintainer a perpetual, worldwide, non-exclusive, royalty-free right to sublicense the contribution under alternative license terms.

The AGPL-3.0 license is irrevocable. Code released under AGPL-3.0 will always remain available under AGPL-3.0.
