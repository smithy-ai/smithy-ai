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

## What it does not do

**No tracker-native sub-issues.** Not every tracker has subtasks, epics or
tasklists, and a parent story may live in Jira while the work lives in
repositories. Children are ordinary issues; the parent/child graph is in Smithy's
run store.

Each child issue does carry a human-readable reference back to the story, which
the tracker turns into a link on both. Nothing reads it back — it is for people.

## Configuring it

The built-in ships inert: an empty catalog and no story repositories. Extend it
and supply configuration:

```yaml
# /config/workflows/acme-coordinator.yml
apiVersion: smithy.ai/v1alpha1
kind: Workflow
metadata:
  name: acme-coordinator
  extends: feature-coordinator

vars:
  # Where feature stories are raised. Never a catalog repository — those hold
  # the work, and their issues are this coordinator's own children.
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

### Two guards, and why both

A coordinator listens for assigned issues, and every child it creates **is** an
assigned issue. Without guards it would plan a feature for each task of the
feature it just planned.

- **`storyRepos`** — it only claims issues raised where stories are raised.
- **`coordinatorUser`** — it only claims issues handed to that actor.

Which actor an issue is assigned to is how a person says what kind of work it is.
That needs a separate account: see [actors](../concepts.md#actors).

## What the agent sees when planning

- The story's own repository at `/story` — read first, because the contract the
  plan has to honour usually lives beside the story rather than in the code.
- The first catalog repository at the workspace root.
- The rest are not cloned; the prompt tells the agent to clone what it needs. With
  a large catalog, cloning everything costs more than the planning turn.

The plan comes back structured — one entry per issue, with `dependsOn` as
zero-based indexes into the plan — so the fan-out iterates data rather than
parsing prose.

## Ordering

An issue is only assigned once everything it depends on has **completed**. A
dependency that was never created cannot block forever, and a child that was
cancelled — someone took the agent off it — never satisfies one. The feature is
complete only when every child completed; if some were abandoned the coordinator
says so on the story rather than going quiet.

## Approving

Either works, and both run the same steps:

- Apply the approval label to the story.
- Press approve on the run in the dashboard, which emits `signal:gate-approved`.

## A worked example

Three repositories: `sample-app` holds a spec and the stories, `sample-app-backend`
owns `greeting.txt`, `sample-app-frontend` renders it into `display.txt` with a
`> ` prefix. The spec says the frontend must render exactly what the backend
provides.

A story — *"the greeting should address the user by name"* — assigned to the
coordinator produced:

1. A plan noting the contract's shape was unchanged, so no spec update was needed.
2. Two issues: backend first, frontend `dependsOn: [0]`.
3. The backend issue assigned; the frontend created but held.
4. Backend implemented `Hello, Ada`, opened a pull request, finished.
5. The frontend released, implemented `> Hello, Ada`, finished.
6. *"Every child issue is done — this feature is complete."*

The frontend agent never saw the backend repository. It got the exact expected
bytes because the coordinator put them in the issue body — which is why the
prompt insists on spelling out cross-component contracts.

## When not to use it

If a feature touches one repository, do not. Assign it to the agent directly:
`smithy-development` plans, implements and opens a pull request with less
ceremony and no approval gate for the split.
