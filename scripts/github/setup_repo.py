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

from setup_lib import APIError, EnvFile, GitHubAPI, find_env_file

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
    smithy_token = env.get("SMITHY_GITHUB_TOKEN")
    if not smithy_token:
        print("Error: SMITHY_GITHUB_TOKEN not found in .env")
        print("Run setup.py first.")
        sys.exit(1)

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

    api = GitHubAPI(smithy_token, api_base)

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
