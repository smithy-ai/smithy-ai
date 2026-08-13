# Coordinating across repositories

`feature-coordinator` takes one feature story, splits it into an issue per
repository, and drives those to completion in dependency order.

```
story assigned to the coordinator
  │
  ├─ reads the story's repository and the catalog, writes a plan
  ├─ posts the plan, waits for a human
  │
  ▼ approved
  ├─ creates one ordinary issue per repository
  ├─ spawns a run to work each one
  └─ assigns only those whose dependencies are already done
        │
        ▼ a child finishes
        └─ releases the next wave, until every child is done
```

## Child issues

Children are ordinary issues, not tracker-native subtasks or epics. The
parent/child graph is held in Smithy's run store, so a story in one system can
produce issues in another.

Each child carries a reference back to the story, which the tracker turns into a
link on both. It is there for people to follow; nothing in Smithy reads it.

## Configuring it

The built-in coordinator has an empty catalog and no story repositories, so on
its own it claims nothing. Extend it and supply both:

```yaml
# /config/workflows/acme-coordinator.yml
apiVersion: smithy.ai/v1alpha1
kind: Workflow
metadata:
  name: acme-coordinator
  extends: feature-coordinator

vars:
  # Where feature stories are raised. Never a catalog repository: the issues
  # there are this coordinator's own children.
  storyRepos: [acme/product]

  catalog:
    - owner: acme
      repo: api
      description: The HTTP API. Contract in the story repo under specs/.
    - owner: acme
      repo: web
      description: The web client.

  # Which connector the catalog lives on. Empty means the same one the story
  # arrived through, which is right when one system tracks both.
  childConnector: ""

  coordinatorUser: coordinator     # who a story is assigned to
  botUser: smithy                  # who child issues are assigned to
```

### Two guards

A coordinator listens for assigned issues, and every child it creates is itself
an assigned issue, so it needs both guards to avoid claiming its own children:

- **`storyRepos`**: it only claims issues raised where stories are raised.
- **`coordinatorUser`**: it only claims issues handed to that actor.

The coordinator therefore needs an account of its own; see
[actors](../concepts.md#actors).

## What the agent sees when planning

- The story's own repository at `/story`, which usually holds the contract the
  plan has to honour.
- The first catalog repository at the workspace root.
- The rest are not cloned; the prompt tells the agent to clone the ones it needs.

The plan comes back structured: one entry per issue, with `dependsOn` as
zero-based indexes into the plan.

## Ordering

An issue is only assigned once everything it depends on has completed. A
dependency that was never created does not block, and a cancelled child does not
satisfy one. The feature is complete only when every child completed; if any were
cancelled or failed, the coordinator says so on the story.

## Approving

Either works, and both run the same steps:

- Apply the approval label to the story.
- Press approve on the run in the dashboard, which emits `signal:gate-approved`.

## A worked example

Three repositories: `sample-app` holds a spec and the stories, `sample-app-backend`
owns `greeting.txt`, `sample-app-frontend` renders it into `display.txt` with a
`> ` prefix. The spec says the frontend must render exactly what the backend
provides.

A story assigned to the coordinator, *"the greeting should address the user by
name"*, produced:

1. A plan noting the contract's shape was unchanged, so no spec update was needed.
2. Two issues: backend first, frontend `dependsOn: [0]`.
3. The backend issue assigned; the frontend created but held.
4. Backend implemented `Hello, Ada`, opened a pull request, finished.
5. The frontend released, implemented `> Hello, Ada`, finished.
6. *"Every child issue is done. This feature is complete."*

The frontend agent never saw the backend repository; it worked from the expected
bytes written into its issue body. Cross-component contracts have to be spelled
out in the issue for this reason.

## When not to use it

If a change touches one repository, assign it to the agent directly.
`smithy-development` plans, implements and opens a pull request without the
extra approval gate for the split.
