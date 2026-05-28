#!/usr/bin/env python3
"""One-time GitHub setup for Smithy-AI.

Validates bot account tokens, generates a webhook secret, and writes all
values to a .env file. Fully idempotent — safe to re-run.

If you are already logged in via the gh CLI, the script will offer to pull
tokens directly from it — no manual copy-paste needed.

Usage:
    python3 scripts/github/setup.py [--env /path/to/.env]
    python3 scripts/github/setup.py --github-url https://github.example.com
"""

from __future__ import annotations

import argparse
import getpass
import subprocess
import sys
from pathlib import Path

from setup_lib import APIError, EnvFile, GitHubAPI, find_env_file, generate_secret


def gh_token_for(username: str) -> str:
    """Try to retrieve a token for a gh-authenticated account."""
    try:
        result = subprocess.run(
            ["gh", "auth", "token", "--user", username],
            capture_output=True, text=True, timeout=10,
        )
        token = result.stdout.strip()
        if result.returncode == 0 and token:
            return token
    except (FileNotFoundError, subprocess.TimeoutExpired):
        pass
    return ""


def gh_logged_in_users() -> list:
    """Return list of usernames currently logged in via gh."""
    try:
        result = subprocess.run(
            ["gh", "auth", "status", "--json", "username"],
            capture_output=True, text=True, timeout=10,
        )
        if result.returncode == 0 and result.stdout.strip():
            import json
            data = json.loads(result.stdout)
            if isinstance(data, list):
                return [entry.get("username", "") for entry in data if entry.get("username")]
            if isinstance(data, dict):
                return [data.get("username", "")] if data.get("username") else []
    except Exception:
        pass
    return []


def validate_token(api: GitHubAPI, role: str) -> str:
    """Validate token and return the login username."""
    try:
        user = api.current_user()
        login = user.get("login", "")
        name = user.get("name") or login
        print(f"    Authenticated as: {login} ({name})")
        return login
    except APIError as e:
        print(f"    Error: token for {role} is invalid — {e}")
        sys.exit(1)


def prompt_token(role: str, env: EnvFile, env_key: str, api_base: str) -> str:
    """Get token from .env, gh CLI, or interactive prompt — in that order."""
    # 1. Already in .env
    existing = env.get(env_key)
    if existing:
        print(f"    {env_key} already set in .env — using existing value")
        return existing

    # 2. Offer gh CLI accounts
    gh_users = gh_logged_in_users()
    if gh_users:
        print(f"    Logged-in gh accounts: {', '.join(gh_users)}")
        username = input(f"    Use which account for {role}? (press Enter to type token manually): ").strip()
        if username:
            token = gh_token_for(username)
            if token:
                print(f"    Token retrieved from gh CLI for @{username}")
                return token
            print(f"    Could not retrieve token for @{username} — falling back to manual input")

    # 3. Manual input
    token = getpass.getpass(f"    GitHub token for {role} bot ({env_key}): ").strip()
    if not token:
        print(f"    Error: token cannot be empty")
        sys.exit(1)
    return token


def main():
    parser = argparse.ArgumentParser(description="One-time GitHub setup for Smithy-AI")
    parser.add_argument("--env", metavar="FILE", help="Path to .env file (auto-detected if omitted)")
    parser.add_argument(
        "--github-url",
        metavar="URL",
        default="",
        help="GitHub Enterprise base URL (leave empty for github.com)",
    )
    args = parser.parse_args()

    env_path = Path(args.env) if args.env else find_env_file()
    env = EnvFile(env_path)

    github_url = args.github_url or env.get("GITHUB_URL") or ""
    api_base = (
        "https://api.github.com"
        if not github_url or github_url.rstrip("/") == "https://github.com"
        else github_url.rstrip("/") + "/api/v3"
    )

    print("==> GitHub setup for Smithy-AI")
    if github_url:
        print(f"    GitHub Enterprise URL: {github_url}")
    print()

    # ── Step 1: Smithy token ─────────────────────────────────
    print("==> Validating smithy bot token...")
    smithy_token = prompt_token("smithy", env, "SMITHY_GITHUB_TOKEN", api_base)
    smithy_api = GitHubAPI(smithy_token, api_base)
    smithy_login = validate_token(smithy_api, "smithy")

    # ── Step 2: Architect token ──────────────────────────────
    print()
    print("==> Validating architect bot token...")
    architect_token = prompt_token("architect", env, "ARCHITECT_GITHUB_TOKEN", api_base)
    architect_api = GitHubAPI(architect_token, api_base)
    architect_login = validate_token(architect_api, "architect")

    # ── Step 3: Webhook secret ───────────────────────────────
    print()
    print("==> Generating webhook secret...")
    if env.get("GITHUB_WEBHOOK_SECRET"):
        print("    Using existing GITHUB_WEBHOOK_SECRET from .env")
    else:
        env.set("GITHUB_WEBHOOK_SECRET", generate_secret())
        print("    Generated GITHUB_WEBHOOK_SECRET")

    # ── Step 4: Write .env ───────────────────────────────────
    print()
    print("==> Writing configuration...")
    env.set("VCS_PROVIDER", "github")
    env.set("SMITHY_GITHUB_TOKEN", smithy_token)
    env.set("ARCHITECT_GITHUB_TOKEN", architect_token)
    env.set("SMITHY_BOT_USER", smithy_login)
    env.set("ARCHITECT_BOT_USER", architect_login)
    if github_url:
        env.set("GITHUB_URL", github_url)

    env.save()

    print()
    print("==> Setup complete.")
    print()
    print("Next steps:")
    print("  1. Set CLAUDE_CODE_OAUTH_TOKEN in your .env (run `claude setup-token`)")
    print("  2. Run setup_repo.py for each repository you want Smithy to work on:")
    print(f"       python3 scripts/github/setup_repo.py owner/repo")


if __name__ == "__main__":
    main()
