#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Per-repository GitHub setup for Smithy-AI.

Adds bot collaborators, creates a webhook, creates the "Plan Approved" label,
and optionally creates a context repository for The Architect.
Fully idempotent — safe to re-run.

Usage:
    python3 scripts/github/setup_repo.py owner/repo [--env /path/to/.env]
    python3 scripts/github/setup_repo.py owner/repo --orchestrator-url https://smithy.example.com
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import List

import subprocess

from setup_lib import APIError, EnvFile, GitHubAPI, find_env_file


def get_owner_token(owner: str, env: EnvFile, api_base: str) -> str:
    """Get a token that has admin access to the repo (the repo owner's token).

    Tries in order:
    1. Active gh CLI account — if it matches the repo owner, use its token
    2. Any gh CLI account that matches the repo owner
    3. SMITHY_GITHUB_TOKEN from .env (fallback, may lack admin rights)
    """
    try:
        # Check which gh account is active and what its login is
        result = subprocess.run(
            ["gh", "auth", "status"],
            capture_output=True, text=True, timeout=10,
        )
        output = result.stdout + result.stderr

        import re
        # Find "Active account: true" block to get active user
        active_match = re.search(
            r"account ([^\s(]+).*?Active account: true", output, re.DOTALL
        )
        if active_match:
            active_user = active_match.group(1)
            token_result = subprocess.run(
                ["gh", "auth", "token", "--user", active_user],
                capture_output=True, text=True, timeout=10,
            )
            token = token_result.stdout.strip()
            if token:
                # Verify this token owns the repo
                api = GitHubAPI(token, api_base)
                try:
                    user = api.current_user()
                    if user.get("login", "").lower() == owner.lower():
                        print(f"    Using gh CLI token for @{active_user} (repo owner)")
                        return token
                except Exception:
                    pass

        # Try any gh account that matches the owner
        all_users = re.findall(r"account ([^\s(]+)", output)
        for user in all_users:
            if user.lower() == owner.lower():
                token_result = subprocess.run(
                    ["gh", "auth", "token", "--user", user],
                    capture_output=True, text=True, timeout=10,
                )
                token = token_result.stdout.strip()
                if token:
                    print(f"    Using gh CLI token for @{user} (repo owner)")
                    return token
    except Exception:
        pass

    # Fallback: smithy token (may not have admin rights on private repos)
    token = env.get("SMITHY_GITHUB_TOKEN")
    if token:
        print("    Warning: using SMITHY_GITHUB_TOKEN — may lack admin rights")
        print(f"    Tip: run `gh auth login` as @{owner} to avoid this")
        return token

    print("Error: no usable GitHub token found. Run `gh auth login` as the repo owner or run setup.py first.")
    import sys
    sys.exit(1)

MAIN_REPO_EVENTS = [
    "issues",
    "issue_comment",
    "push",
    "pull_request",
    "pull_request_review",
    "pull_request_review_comment",
    "workflow_run",
]

CONTEXT_REPO_EVENTS = [
    "issue_comment",
    "pull_request",
    "pull_request_review",
    "pull_request_review_comment",
]

LABELS = [
    {"name": "Plan Approved", "color": "009800", "description": "Approved to start implementation"},
]


# ── Collaborators ────────────────────────────────────────────

def add_collaborator(api: GitHubAPI, owner: str, repo: str, username: str):
    """Add a collaborator with push (write) access. PUT is idempotent."""
    try:
        api.put(f"/repos/{owner}/{repo}/collaborators/{username}", {"permission": "push"})
        print(f"    {username} added as collaborator on {owner}/{repo}")
    except APIError as e:
        # 422 = already a collaborator with same or higher permission
        if e.status == 422:
            print(f"    {username} is already a collaborator on {owner}/{repo}")
        else:
            raise


# ── Webhook ──────────────────────────────────────────────────

def ensure_webhook(api: GitHubAPI, owner: str, repo: str, webhook_url: str, secret: str, events: List[str]):
    """Create webhook if one pointing to our URL doesn't already exist."""
    try:
        hooks = api.get(f"/repos/{owner}/{repo}/hooks") or []
        for hook in hooks:
            if hook.get("config", {}).get("url") == webhook_url:
                print(f"    Webhook already exists on {owner}/{repo} — skipping")
                return
    except APIError:
        pass

    api.post(
        f"/repos/{owner}/{repo}/hooks",
        {
            "name": "web",
            "active": True,
            "events": events,
            "config": {
                "url": webhook_url,
                "content_type": "json",
                "secret": secret,
                "insecure_ssl": "0",
            },
        },
    )
    print(f"    Webhook created on {owner}/{repo}")


# ── Labels ───────────────────────────────────────────────────

def ensure_labels(api: GitHubAPI, owner: str, repo: str):
    """Create labels that don't already exist."""
    existing_names: set[str] = set()
    try:
        existing = api.get(f"/repos/{owner}/{repo}/labels?per_page=100") or []
        existing_names = {label.get("name", "") for label in existing}
    except APIError:
        pass

    for label in LABELS:
        if label["name"] in existing_names:
            print(f"    Label already exists: {label['name']}")
        else:
            api.post(f"/repos/{owner}/{repo}/labels", label)
            print(f"    Created label: {label['name']}")


# ── Context repo ─────────────────────────────────────────────

def ensure_context_repo(api: GitHubAPI, owner: str, repo_name: str) -> str:
    """Create the context repository if it doesn't exist. Returns 'owner/context-repo'."""
    context_name = f"{repo_name}-context"
    context_path = f"{owner}/{context_name}"

    try:
        api.get(f"/repos/{context_path}")
        print(f"    Context repo already exists: {context_path}")
        return context_path
    except APIError as e:
        if e.status != 404:
            raise

    body = {
        "name": context_name,
        "description": f"Architectural context for {repo_name}, maintained by The Architect",
        "auto_init": True,
        "private": False,
    }

    # Create under org if owner is an org, otherwise under the authenticated user
    try:
        if api.is_org(owner):
            api.post(f"/orgs/{owner}/repos", body)
        else:
            api.post("/user/repos", body)
        print(f"    Created context repo: {context_path}")
    except APIError as e:
        if e.status == 422:
            print(f"    Context repo already exists: {context_path}")
        else:
            raise

    return context_path


# ── Main ─────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="Per-repository GitHub setup for Smithy-AI")
    parser.add_argument("repo", nargs="?", help="Repository in owner/repo format")
    parser.add_argument("--env", metavar="FILE", help="Path to .env file (auto-detected if omitted)")
    parser.add_argument(
        "--orchestrator-url",
        metavar="URL",
        help="Public URL of the orchestrator (e.g. https://smithy.example.com)",
    )
    parser.add_argument(
        "--no-context-repo",
        action="store_true",
        help="Skip creation of the context repository for The Architect",
    )
    args = parser.parse_args()

    # Resolve repo
    repo = args.repo
    if not repo:
        repo = input("Repository (owner/repo): ").strip()
    if "/" not in repo:
        print("Error: repository must be in owner/repo format")
        sys.exit(1)
    owner, repo_name = repo.split("/", 1)

    print(f"==> Setting up repository: {owner}/{repo_name}")
    print()

    # Load .env
    env_path = Path(args.env) if args.env else find_env_file()
    env = EnvFile(env_path)

    # Resolve orchestrator URL → webhook URL
    orchestrator_url = (
        args.orchestrator_url
        or env.get("SMITHY_HOST") and f"https://{env.get('SMITHY_HOST')}"
        or ""
    )
    if not orchestrator_url:
        orchestrator_url = input("Orchestrator public URL (e.g. https://smithy.example.com): ").strip()
    if not orchestrator_url:
        print("Error: orchestrator URL is required to configure the webhook")
        sys.exit(1)
    webhook_url = orchestrator_url.rstrip("/") + "/webhooks/github"

    # Load required config from .env
    webhook_secret = env.get("GITHUB_WEBHOOK_SECRET")
    if not webhook_secret:
        print("Error: GITHUB_WEBHOOK_SECRET not found in .env")
        print("Run setup.py first.")
        sys.exit(1)

    smithy_bot = env.get("SMITHY_BOT_USER") or "smithy"
    architect_bot = env.get("ARCHITECT_BOT_USER") or "architect"

    github_url = env.get("GITHUB_URL") or ""
    api_base = (
        "https://api.github.com"
        if not github_url or github_url.rstrip("/") == "https://github.com"
        else github_url.rstrip("/") + "/api/v3"
    )

    # Use the repo owner's token for admin operations (adding collaborators, webhooks, labels).
    # Fall back to the active gh CLI account, then SMITHY_GITHUB_TOKEN.
    owner_token = get_owner_token(owner, env, api_base)
    api = GitHubAPI(owner_token, api_base)

    # Verify repo exists and is accessible before proceeding
    print(f"==> Verifying access to {owner}/{repo_name}...")
    try:
        repo_data = api.get(f"/repos/{owner}/{repo_name}")
        print(f"    ✓ Repository found: {repo_data.get('full_name')} ({'private' if repo_data.get('private') else 'public'})")
    except APIError as e:
        if e.status == 404:
            print(f"    ✗ Repository '{owner}/{repo_name}' not found.")
            print(f"    Make sure the repo exists and the token has access to it.")
            print(f"    If it's a private repo, ensure you're authenticated as @{owner}.")
            sys.exit(1)
        raise

    # ── Step 1: Add collaborators to main repo ───────────────
    print(f"==> Adding collaborators to {owner}/{repo_name}...")
    add_collaborator(api, owner, repo_name, smithy_bot)
    add_collaborator(api, owner, repo_name, architect_bot)

    # ── Step 2: Webhook on main repo ─────────────────────────
    print(f"==> Setting up webhook on {owner}/{repo_name}...")
    print(f"    Webhook URL: {webhook_url}")
    ensure_webhook(api, owner, repo_name, webhook_url, webhook_secret, MAIN_REPO_EVENTS)

    # ── Step 3: Labels ────────────────────────────────────────
    print(f"==> Creating labels on {owner}/{repo_name}...")
    ensure_labels(api, owner, repo_name)

    # ── Step 4: Context repository (optional) ────────────────
    if not args.no_context_repo:
        print(f"==> Setting up context repository...")
        context_owner, context_repo = ensure_context_repo(api, owner, repo_name).split("/", 1)

        print(f"==> Adding collaborators to {context_owner}/{context_repo}...")
        add_collaborator(api, context_owner, context_repo, smithy_bot)
        add_collaborator(api, context_owner, context_repo, architect_bot)

        print(f"==> Setting up webhook on {context_owner}/{context_repo}...")
        ensure_webhook(api, context_owner, context_repo, webhook_url, webhook_secret, CONTEXT_REPO_EVENTS)
    else:
        print("==> Skipping context repository (--no-context-repo)")

    print()
    print(f"==> Repository setup complete.")
    print()
    print("Next steps:")
    print(f"  • Open a GitHub issue and assign it to @{smithy_bot} to start a workflow")
    print(f"  • Apply the 'Plan Approved' label to trigger implementation")
    print(f"  • Request a review from @{architect_bot} on a PR for a knowledge-base review")


if __name__ == "__main__":
    main()
