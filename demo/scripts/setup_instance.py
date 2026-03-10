#!/usr/bin/env python3
"""One-time Forgejo instance setup.

Creates bot users, API tokens, secrets, and OAuth2 app.
Fully idempotent — safe to re-run. Auto-writes values to .env.

Usage:
    python3 scripts/setup_instance.py
"""

import base64
import subprocess
import sys
from pathlib import Path

from setup_lib import (
    APIError,
    EnvFile,
    ForgejoAPI,
    generate_secret,
    prompt_admin_credentials,
    prompt_forgejo_url,
)

SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_DIR = SCRIPT_DIR.parent


def create_bot_user(
    api: ForgejoAPI, username: str, email: str, full_name: str
) -> str | None:
    """Create a bot user if it doesn't exist. Returns the password if newly created."""
    try:
        api.get(f"/users/{username}")
        print(f"    User already exists: {username}")
        return None
    except APIError as e:
        if e.status != 404:
            raise

    password = generate_secret(16)
    api.post(
        "/admin/users",
        {
            "username": username,
            "email": email,
            "full_name": full_name,
            "password": password,
            "must_change_password": False,
            "visibility": "public",
        },
    )
    print(f"    Created user: {username}")
    return password


def set_avatar(api: ForgejoAPI, username: str, password: str | None, avatar_path: Path):
    """Set user avatar. Only works when we have the password (first run)."""
    if password is None:
        print(f"    Skipping avatar for {username} (user already existed)")
        return
    if not avatar_path.exists():
        print(f"    Avatar file not found: {avatar_path} — skipping")
        return

    avatar_b64 = base64.b64encode(avatar_path.read_bytes()).decode()
    # Use the bot's own credentials to set its avatar
    bot_api = ForgejoAPI(
        api.api_url.rsplit("/api/v1", 1)[0], username, password
    )
    try:
        bot_api.post("/user/avatar", {"image": f"data:image/png;base64,{avatar_b64}"})
        print(f"    Avatar set for {username}")
    except APIError:
        print(f"    Could not set avatar for {username} — skipping")


def get_or_create_token(
    api: ForgejoAPI, username: str, token_name: str, env: EnvFile, env_key: str
):
    """Get existing token from .env or create a new one."""
    existing = env.get(env_key)
    if existing:
        print(f"    Using existing {env_key} from .env")
        return

    # Check if token with this name already exists
    try:
        tokens = api.get(f"/users/{username}/tokens")
        if tokens and any(t.get("name") == token_name for t in tokens):
            print(f"    Token '{token_name}' already exists but not in .env — cannot retrieve")
            print(f"    Delete the token in Forgejo and re-run, or add {env_key} to .env manually")
            return
    except APIError:
        pass

    # Create new token
    result = api.post(
        f"/users/{username}/tokens",
        {"name": token_name, "scopes": ["all"]},
    )
    token = result.get("sha1", "")
    if token:
        env.set(env_key, token)
        print(f"    Token created: {token[:8]}...")
    else:
        print(f"    Failed to create token for {username}")


def get_or_create_oauth2_app(api: ForgejoAPI, env: EnvFile, orchestrator_url: str):
    """Register OAuth2 application if it doesn't exist."""
    app_name = "smithy-dashboard"

    existing_id = env.get("OAUTH2_CLIENT_ID")
    existing_secret = env.get("OAUTH2_CLIENT_SECRET")
    if existing_id and existing_secret:
        print(f"    Using existing OAuth2 credentials from .env")
        return

    # Check if app already exists
    try:
        apps = api.get("/user/applications/oauth2")
        if apps:
            for app in apps:
                if app.get("name") == app_name:
                    client_id = app.get("client_id", "")
                    print(f"    OAuth2 app already exists (client_id: {client_id[:8]}...)")
                    env.set("OAUTH2_CLIENT_ID", client_id)
                    if not existing_secret:
                        print(f"    Warning: OAUTH2_CLIENT_SECRET not in .env and cannot be retrieved")
                        print(f"    Delete the OAuth2 app in Forgejo and re-run to regenerate")
                    return
    except APIError:
        pass

    # Create new app
    result = api.post(
        "/user/applications/oauth2",
        {
            "name": app_name,
            "redirect_uris": [f"{orchestrator_url}/api/auth/callback"],
            "confidential_client": True,
        },
    )
    client_id = result.get("client_id", "")
    client_secret = result.get("client_secret", "")
    env.set("OAUTH2_CLIENT_ID", client_id)
    env.set("OAUTH2_CLIENT_SECRET", client_secret)
    print(f"    OAuth2 app created (client_id: {client_id[:8]}...)")


def get_runner_token(env: EnvFile):
    """Generate a runner registration token if not already in .env."""
    if env.get("RUNNER_TOKEN"):
        print("    Using existing RUNNER_TOKEN from .env")
        return

    try:
        result = subprocess.run(
            ["docker", "compose", "exec", "-T", "-u", "git", "forgejo",
             "forgejo", "actions", "generate-runner-token"],
            capture_output=True, text=True, timeout=30,
        )
        token = result.stdout.strip()
        if token:
            env.set("RUNNER_TOKEN", token)
            print(f"    Runner token: {token[:8]}...")
        else:
            print("    Warning: Could not generate runner token")
            print(f"    stderr: {result.stderr.strip()}")
    except (subprocess.TimeoutExpired, FileNotFoundError) as e:
        print(f"    Warning: Could not generate runner token — {e}")


def main():
    print("==> Forgejo instance setup")
    print()

    forgejo_url = prompt_forgejo_url()
    username, password = prompt_admin_credentials()

    api = ForgejoAPI(forgejo_url, username, password)
    env = EnvFile()

    # Preserve standard config values
    env.set("FORGEJO_URL", "http://forgejo:3000")
    env.set("DOCKER_NETWORK", "forgejo-net")
    env.set("TASK_IMAGE", "claude-task:latest")
    env.set("CACHE_VOLUMES", "pnpm,npm")
    env.set("FORGEJO_EXTERNAL_URL", "http://localhost:3000")

    # Step 1: Create bot users
    print("==> Creating bot users...")
    smithy_pass = create_bot_user(api, "smithy", "smithy@localhost", "Agent Smithy")
    set_avatar(api, "smithy", smithy_pass, SCRIPT_DIR / "smithy-avatar.png")

    architect_pass = create_bot_user(
        api, "architect", "architect@localhost", "The Architect"
    )
    set_avatar(
        api, "architect", architect_pass, SCRIPT_DIR / "architect-avatar.png"
    )

    # Step 2: Create API tokens
    print("==> Creating API tokens...")
    get_or_create_token(api, "smithy", "smithy-orchestrator", env, "SMITHY_FORGEJO_TOKEN")
    get_or_create_token(
        api, "architect", "architect-orchestrator", env, "ARCHITECT_FORGEJO_TOKEN"
    )

    # Step 3: Generate secrets
    print("==> Generating secrets...")

    if not env.get("WEBHOOK_SECRET"):
        env.set("WEBHOOK_SECRET", generate_secret())
        print("    Generated WEBHOOK_SECRET")
    else:
        print("    Using existing WEBHOOK_SECRET from .env")

    if not env.get("JWT_SECRET"):
        env.set("JWT_SECRET", generate_secret())
        print("    Generated JWT_SECRET")
    else:
        print("    Using existing JWT_SECRET from .env")

    # Step 4: Runner token
    print("==> Getting runner registration token...")
    get_runner_token(env)

    # Step 5: OAuth2 application
    orchestrator_url = env.get("ORCHESTRATOR_URL") or "http://localhost:8000"
    env.set("ORCHESTRATOR_URL", orchestrator_url)

    print("==> Registering OAuth2 application...")
    get_or_create_oauth2_app(api, env, orchestrator_url)

    # Save .env
    env.save()
    print()
    print(f"==> Setup complete. Values written to {env.path}")


if __name__ == "__main__":
    main()
