#!/usr/bin/env python3
"""Per-repo Forgejo setup.

Configures a repository for use with the orchestrator: adds bot collaborators,
creates webhook, labels, and a context repository.
Fully idempotent — safe to re-run.

Usage:
    python3 scripts/setup_repo.py [owner/repo]
"""

import sys

from setup_lib import (
    APIError,
    EnvFile,
    ForgejoAPI,
    prompt_admin_credentials,
    prompt_forgejo_url,
)

WEBHOOK_URL = "http://orchestrator:8080/webhooks/forgejo"

MAIN_REPO_EVENTS = [
    "issues",
    "issue_comment",
    "push",
    "pull_request",
    "pull_request_comment",
    "action_run_failure",
    "action_run_recover",
]

CONTEXT_REPO_EVENTS = [
    "issue_comment",
    "pull_request",
    "pull_request_comment",
]

LABELS = [
    {"name": "Plan Approved", "color": "#009800"},
]


def add_collaborator(api: ForgejoAPI, repo_path: str, username: str):
    """Add a collaborator to a repo (PUT is idempotent)."""
    api.put(f"/repos/{repo_path}/collaborators/{username}", {"permission": "write"})
    print(f"    {username} added as collaborator on {repo_path}")


def ensure_webhook(api: ForgejoAPI, repo_path: str, webhook_secret: str, events: list[str]):
    """Create webhook if one with our URL doesn't already exist."""
    try:
        hooks = api.get(f"/repos/{repo_path}/hooks")
        if hooks:
            for hook in hooks:
                if hook.get("config", {}).get("url") == WEBHOOK_URL:
                    print(f"    Webhook already exists on {repo_path} — skipping")
                    return
    except APIError:
        pass

    api.post(
        f"/repos/{repo_path}/hooks",
        {
            "type": "forgejo",
            "active": True,
            "config": {
                "url": WEBHOOK_URL,
                "content_type": "json",
                "secret": webhook_secret,
            },
            "events": events,
        },
    )
    print(f"    Webhook created on {repo_path}")


def ensure_labels(api: ForgejoAPI, repo_path: str):
    """Create labels that don't already exist."""
    existing_names = set()
    try:
        existing = api.get(f"/repos/{repo_path}/labels")
        if existing:
            existing_names = {label.get("name") for label in existing}
    except APIError:
        pass

    for label in LABELS:
        if label["name"] in existing_names:
            print(f"    Label already exists: {label['name']}")
        else:
            api.post(f"/repos/{repo_path}/labels", label)
            print(f"    Created label: {label['name']}")


def ensure_context_repo(api: ForgejoAPI, owner: str, repo_name: str):
    """Create the context repository if it doesn't exist."""
    context_repo = f"{repo_name}-context"
    repo_path = f"{owner}/{context_repo}"

    try:
        api.get(f"/repos/{repo_path}")
        print(f"    Context repo already exists: {repo_path}")
    except APIError as e:
        if e.status != 404:
            raise
        api.post(
            f"/orgs/{owner}/repos",
            {
                "name": context_repo,
                "description": f"Architectural context for {repo_name}, maintained by The Architect",
                "auto_init": True,
            },
        )
        print(f"    Created context repo: {repo_path}")

    return repo_path


def main():
    # Get repo from argument or prompt
    if len(sys.argv) > 1:
        repo = sys.argv[1]
    else:
        repo = input("Repository (owner/repo): ").strip()

    if "/" not in repo:
        print("Error: repository must be in owner/repo format")
        sys.exit(1)

    owner, repo_name = repo.split("/", 1)
    repo_path = f"{owner}/{repo_name}"

    print(f"==> Setting up repository: {repo_path}")
    print()

    forgejo_url = prompt_forgejo_url()
    username, password = prompt_admin_credentials()

    api = ForgejoAPI(forgejo_url, username, password)
    env = EnvFile()

    webhook_secret = env.get("WEBHOOK_SECRET")
    if not webhook_secret:
        print("Error: WEBHOOK_SECRET not found in .env")
        print("Run setup_instance.py first to generate secrets.")
        sys.exit(1)

    # Step 1: Add bot collaborators to main repo
    print(f"==> Adding collaborators to {repo_path}...")
    add_collaborator(api, repo_path, "smithy")
    add_collaborator(api, repo_path, "architect")

    # Step 2: Create webhook on main repo
    print(f"==> Setting up webhook on {repo_path}...")
    ensure_webhook(api, repo_path, webhook_secret, MAIN_REPO_EVENTS)

    # Step 3: Create labels
    print(f"==> Creating labels on {repo_path}...")
    ensure_labels(api, repo_path)

    # Step 4: Create context repository
    print(f"==> Setting up context repository...")
    context_path = ensure_context_repo(api, owner, repo_name)

    # Step 5: Add collaborators to context repo
    print(f"==> Adding collaborators to {context_path}...")
    add_collaborator(api, context_path, "smithy")
    add_collaborator(api, context_path, "architect")

    # Step 6: Create webhook on context repo
    print(f"==> Setting up webhook on {context_path}...")
    ensure_webhook(api, context_path, webhook_secret, CONTEXT_REPO_EVENTS)

    print()
    print("==> Repository setup complete.")


if __name__ == "__main__":
    main()
