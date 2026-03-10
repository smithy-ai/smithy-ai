"""Shared utilities for Forgejo setup scripts."""

import getpass
import json
import os
import secrets
import urllib.error
import urllib.request
from pathlib import Path

ENV_FILE = Path(__file__).resolve().parent.parent / ".env"


class EnvFile:
    """Read/write .env files, preserving existing non-empty values."""

    def __init__(self, path: Path = ENV_FILE):
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
        """Return the value for key, or empty string if missing/empty."""
        return self.values.get(key, "")

    def set(self, key: str, value: str):
        """Set key=value only if the current value is empty or missing."""
        if not self.get(key):
            self.values[key] = value

    def force_set(self, key: str, value: str):
        """Set key=value unconditionally."""
        self.values[key] = value

    def save(self):
        """Write all values to the .env file."""
        lines = []
        for key, value in self.values.items():
            lines.append(f"{key}={value}")
        self.path.write_text("\n".join(lines) + "\n")


class ForgejoAPI:
    """Thin wrapper around urllib.request for Forgejo API calls."""

    def __init__(self, base_url: str, username: str, password: str):
        self.api_url = f"{base_url}/api/v1"
        self.username = username
        self.password = password

    def _auth_header(self) -> str:
        import base64
        credentials = f"{self.username}:{self.password}"
        encoded = base64.b64encode(credentials.encode()).decode()
        return f"Basic {encoded}"

    def request(self, method: str, path: str, data: dict | None = None) -> dict | list | None:
        """Make an authenticated API request. Returns parsed JSON or None on error."""
        url = f"{self.api_url}{path}"
        body = json.dumps(data).encode() if data else None
        req = urllib.request.Request(url, data=body, method=method)
        req.add_header("Authorization", self._auth_header())
        req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req) as resp:
                resp_body = resp.read()
                if resp_body:
                    return json.loads(resp_body)
                return None
        except urllib.error.HTTPError as e:
            raise APIError(e.code, e.read().decode()) from e

    def get(self, path: str) -> dict | list | None:
        return self.request("GET", path)

    def post(self, path: str, data: dict) -> dict | list | None:
        return self.request("POST", path, data)

    def put(self, path: str, data: dict | None = None) -> dict | list | None:
        return self.request("PUT", path, data)

    def delete(self, path: str) -> dict | list | None:
        return self.request("DELETE", path)


class APIError(Exception):
    """Raised when an API call returns an error status."""

    def __init__(self, status: int, body: str):
        self.status = status
        self.body = body
        super().__init__(f"HTTP {status}: {body}")


def prompt_admin_credentials() -> tuple[str, str]:
    """Prompt for Forgejo admin username and password."""
    username = input("Forgejo admin username: ")
    password = getpass.getpass("Forgejo admin password: ")
    return username, password


def prompt_forgejo_url() -> str:
    """Prompt for the Forgejo URL, defaulting to localhost."""
    url = input("Forgejo URL [http://localhost:3000]: ").strip()
    return url or "http://localhost:3000"


def generate_secret(length: int = 32) -> str:
    """Generate a hex secret."""
    return secrets.token_hex(length)
