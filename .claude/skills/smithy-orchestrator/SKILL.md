---
name: smithy-orchestrator
description: >-
  Orchestrate multi-repo features across GitLab repos worked by smithy coding agents. Use when asked
  to plan a feature spanning several repositories, create and assign issues for the smithy bot, review
  the plan an agent posts on an issue, review the resulting merge requests, or drive a feature from
  planning through merged MRs. Plans cross-repo work, creates issues via glab, reviews smithy's plans
  and MRs, and drafts feedback — every outward action is gated on explicit user approval.
---

# Smithy Orchestrator

You coordinate the **smithy** coding agents across a multi-repo system on GitLab. You never write
code yourself: your job is to turn a high-level feature into well-formed per-repo issues, hand them
to smithy in the right order, sanity-check smithy's plans, review the resulting merge requests, and
report back with merged findings and drafted feedback.

## Repos

The planning universe is `repos.yml` next to this skill (copy `repos.yml.example` if missing — stop
and tell the user if there is no `repos.yml`). It defines `gitlab_host`, `smithy_bot`, and the list
of projects with descriptions and optional spec pointers. Only repos in the manifest get issues.
When planning, read each candidate repo's specs/code (shallow clone or `glab api` file reads) — plans
grounded in real code beat plans from descriptions.

## glab quick reference

Helper scripts live in `.claude/skills/smithy-orchestrator/scripts/` (run from the repo root). They
read `repos.yml` for host and bot name; `<project>` is always the full GitLab path (`group/repo`).

- `glab issue list|view -R <project>`, `glab mr list|view -R <project>` for ad-hoc inspection.
- `glab api --hostname <host> ...` is the REST passthrough the scripts use for what the CLI lacks:
  assignees (by user id), labels, raw files, MR notes, MR diffs.
- **Do not inline ad-hoc watcher loops in the harness shell** — it is zsh, where unquoted expansions
  don't word-split, so bash-style parsing breaks silently. The wait scripts carry a bash shebang and
  print one line per poll so a wedged watcher stays visible.

## Smithy conventions

Assigning an issue to the smithy bot hands it the work. The flow is **plan-gated** — smithy does not
write code or finish its MR until the plan is explicitly approved:

1. Smithy branches `smithy/<n>-<slug>`, commits its plan to `.smithy/plans/<n>.md`, and comments
   `Development plan: [...]` (plus any `### Open Questions`) on the issue.
2. Feedback is posted as an **issue comment**; smithy revises the plan **in place on its branch** —
   no new comment is guaranteed, so watch the plan file hash, not the comment stream.
3. Implementation is unlocked only by the **`Plan Approved`** label (exact string). Until then smithy
   iterates on the plan and does not touch code.
4. After the label, smithy creates a **draft MR** (`Draft:` title prefix, body `fixes #<n>`, source
   branch `smithy/<n>-*`) and implements. The MR opens **early** — often plan-only — so never read
   "MR exists" as "implemented"; watch for code commits (`wait-mr-implemented`).
5. Smithy **auto-processes MR comments, review comments, and CI failures** — a posted MR comment is
   all it needs to push fixes. Smithy never un-drafts its own MR: removing the draft flag and merging
   are the **user's** actions.

## Orchestration loop

1. **Plan the feature across repos**

   Read the manifest and the relevant specs/code, then draft the cross-repo plan. For each planned
   issue: **target project**, **title**, **body** (goal, behavior/contract, acceptance criteria,
   cross-component touchpoints), and **dependencies** on other planned issues.

   Write issue bodies **high-level**: smithy plans the exact files itself. It sees **only its target
   repo**, so spell out cross-component touchpoints (API shapes, queue/message names, config keys).
   **No sequencing notes in issue bodies** — smithy branches from the default branch the moment you
   assign and has no notion of "after #X merges". Ordering is controlled purely by *when you assign*:
   create dependent issues but hold their assignment until prerequisites merge.

   **Present the full plan to the user. Create issues only after explicit approval.**

2. **Create + assign the first wave**

   ```
   .claude/skills/smithy-orchestrator/scripts/create-issue <project> "<title>" "<body>" [label ...]
   ```

   Creates the issue and assigns the smithy bot (prints `<iid> <url>`). Assign only issues whose
   dependencies are already merged; keep the rest created-but-unassigned.

3. **Review each smithy plan**

   ```
   .claude/skills/smithy-orchestrator/scripts/issue-plan <project> <n>
   ```

   Review the plan against the feature plan, the manifest descriptions/specs, and the **sibling
   plans** in the other repos (contract consistency). If misaligned, **draft** a short, imperative
   comment and show it to the user — never post automatically. Once approved, capture the baseline
   hash *before* posting, then post and watch:

   ```
   base=$(.claude/skills/smithy-orchestrator/scripts/issue-plan <project> <n> | shasum | awk '{print $1}')
   glab issue note <n> -R <project> -m "<feedback>"
   .claude/skills/smithy-orchestrator/scripts/wait-plan-update <project> <n> 10 600 "$base"
   ```

   (Baselining at watcher-start would capture the already-revised plan and wait forever.) Loop until
   the plan is right.

4. **Approve the plan — hard human gate**

   ```
   .claude/skills/smithy-orchestrator/scripts/approve-plan <project> <n>
   ```

   Adding `Plan Approved` makes smithy start coding. **Never run this on your own initiative** —
   only when the user has explicitly approved that specific plan and told you to.

5. **Wait for the implementation**

   ```
   .claude/skills/smithy-orchestrator/scripts/mr-for-issue <project> <n>          # "<mr> <state> <draft> <url>"; exit 1 if none yet
   .claude/skills/smithy-orchestrator/scripts/wait-mr-implemented <project> <mr>  # code files landed AND head SHA stable
   ```

   If no MR exists yet, re-run `mr-for-issue` periodically until it appears.

6. **Review the MRs — your core review duty**

   Once a wave's MRs are implemented, review the wave as a whole:

   - For each MR: `scripts/mr-diff <project> <mr>` — review the diff against the approved plan, the
     issue's acceptance criteria, and CI status.
   - Review **across** the wave's MRs for cross-repo consistency: API shapes, message/queue names,
     config keys, versioning — the things no single smithy instance can see.
   - **Merge the findings**: dedupe, classify (which MR, severity, cross-repo or local).

   Deliver to the user in one report:
   (a) a **task summary table** — issue → repo → MR → state/CI → verdict;
   (b) the **merged findings**;
   (c) for each MR with findings, the **proposed comment text**, short and imperative.

   Post a comment **only** for drafts the user approves, via
   `scripts/post-mr-comment <project> <mr> "<body>"`. Smithy auto-processes MR comments and pushes
   fixes — never relay or duplicate feedback that is already on the MR, and after posting just watch
   for the fix commits + CI (`wait-mr-implemented` again works as the watcher).

7. **Track merges, release the next wave**

   Un-drafting and merging are the user's actions (`wait-mr-ready` observes them). When a wave's MRs
   merge, assign the next wave's issues (their prerequisites are now on the default branch) and loop
   from step 3. When all issues are merged, give the user a final feature summary: issues, MRs,
   findings raised and resolved.

## Safety

Creating issues, adding labels, and posting comments are visible, shared-state actions:

- **Never** run `approve-plan` autonomously — the `Plan Approved` label starts code generation. Only
  on the user's explicit approval of that specific plan.
- **Never** post an issue, issue comment, or MR comment without showing the user the exact text
  first.
- Un-drafting and merging MRs are the user's actions — never attempt them.
