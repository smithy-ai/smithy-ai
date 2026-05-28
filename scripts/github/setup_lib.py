"""Shared utilities for GitHub setup scripts."""

import getpass
import json
import os
import secrets
import urllib.error
import urllib.request
from pathlib import Path


class EnvFile:
    """Read/write .env files, preserving existing non-empty values."""

    def __init__(self, path: Path):
        self.path = path
        self.values: dict[str, str] = {}
        self._load()

    def _load(self):
        if not self.path.exists():
            return
        for line in self.path.read_text().splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            if "=" not in line:
                continue
            key, _, value = line.partition("=")
            self.values[key.strip()] = value.strip()

    def get(self, key: str) -> str:
        return self.values.get(key, "")

    def set(self, key: str, value: str):
        """Set key=value only if the current value is empty."""
        if not self.get(key):
            self.values[key] = value

    def force_set(self, key: str, value: str):
        self.values[key] = value

    def save(self):
        lines = []
        for key, value in self.values.items():
            lines.append(f"{key}={value}")
        self.path.write_text("\n".join(lines) + "\n")
        print(f"    Written to {self.path}")


class GitHubAPI:
    """Thin wrapper around urllib.request for the GitHub REST API."""

    def __init__(self, token: str, base_url: str = "https://api.github.com"):
        self.api_url = base_url.rstrip("/")
        self.token = token

    def request(self, method: str, path: str, data: dict | None = None) -> dict | list | None:
        url = f"{self.api_url}{path}"
        body = json.dumps(data).encode() if data is not None else None
        req = urllib.request.Request(url, data=body, method=method)
        req.add_header("Authorization", f"Bearer {self.token}")
        req.add_header("Accept", "application/vnd.github+json")
        req.add_header("X-GitHub-Api-Version", "2022-11-28")
        if body:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req) as resp:
                body = resp.read()
                return json.loads(body) if body else None
        except urllib.error.HTTPError as e:
            raise APIError(e.code, e.read().decode()) from e

    def get(self, path: str) -> dict | list | None:
        return self.request("GET", path)

    def post(self, path: str, data: dict) -> dict | list | None:
        return self.request("POST", path, data)

    def put(self, path: str, data: dict | None = None) -> dict | list | None:
        return self.request("PUT", path, data)

    def delete(self, path: str) -> None:
        self.request("DELETE", path)

    def current_user(self) -> dict:
        return self.get("/user")

    def is_org(self, owner: str) -> bool:
        try:
            result = self.get(f"/orgs/{owner}")
            return result is not None
        except APIError as e:
            if e.status == 404:
                return False
            raise


class APIError(Exception):
    def __init__(self, status: int, body: str):
        self.status = status
        self.body = body
        super().__init__(f"HTTP {status}: {body}")


def generate_secret(length: int = 32) -> str:
    return secrets.token_hex(length)


def find_env_file() -> Path:
    """Look for .env upward from cwd, or use examples/full/.env relative to repo root."""
    # Walk up from cwd
    here = Path.cwd()
    for parent in [here, *here.parents]:
        candidate = parent / ".env"
        if candidate.exists():
            return candidate

    # Fall back to examples/full/.env relative to this script's repo root
    script_dir = Path(__file__).resolve().parent
    repo_root = script_dir.parent.parent
    return repo_root / "examples" / "full" / ".env"
