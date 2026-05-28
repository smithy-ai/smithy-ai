#!/usr/bin/env bash
# run.sh — Full Smithy-AI setup and launch for GitHub.
#
# Runs interactively through every step:
#   1. Check prerequisites
#   2. Log in bot accounts via gh CLI (if needed)
#   3. Validate tokens and write .env  (setup.py)
#   4. Ask for CLAUDE_CODE_OAUTH_TOKEN
#   5. Ask which repo to configure
#   6. Start ngrok tunnel (if not running)
#   7. Configure the repo (setup_repo.py)
#   8. Start the orchestrator (docker compose)
#
# Usage:
#   bash scripts/github/run.sh

set -euo pipefail

# ── Colours ──────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BOLD='\033[1m'; RESET='\033[0m'

info()    { echo -e "${GREEN}==>${RESET} ${BOLD}$*${RESET}"; }
warn()    { echo -e "${YELLOW}  ! $*${RESET}"; }
error()   { echo -e "${RED}  ✗ $*${RESET}"; exit 1; }
prompt()  { echo -e "${BOLD}  ? $*${RESET}"; }

# ── Resolve repo root ─────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ENV_FILE="$REPO_ROOT/examples/full/.env"
COMPOSE_FILE="$REPO_ROOT/examples/github-local/docker-compose.yml"

cd "$REPO_ROOT"

echo ""
echo -e "${BOLD}╔══════════════════════════════════════╗${RESET}"
echo -e "${BOLD}║   Smithy-AI — GitHub Setup & Launch  ║${RESET}"
echo -e "${BOLD}╚══════════════════════════════════════╝${RESET}"
echo ""

# ── Step 1: Prerequisites ─────────────────────────────────────
info "Checking prerequisites..."

check_cmd() {
    if ! command -v "$1" &>/dev/null; then
        error "$1 is required but not installed. $2"
    fi
    echo "    ✓ $1"
}

check_cmd docker   "Install from https://docs.docker.com/get-docker/"
check_cmd gh       "Install from https://cli.github.com/"
check_cmd ngrok    "Install from https://ngrok.com/download"
check_cmd python3  "Install Python 3.8+"

echo ""

# ── Step 2: gh CLI bot accounts ──────────────────────────────
info "Checking gh CLI accounts..."

GH_STATUS=$(gh auth status 2>&1 || true)

ensure_gh_login() {
    local role="$1"
    echo ""
    warn "No gh account found for the $role bot."
    echo "    Please log in as your $role bot GitHub account."
    echo "    A browser window will open — sign in as the bot account."
    echo ""
    read -rp "    Press Enter when ready to log in as the $role bot..."
    gh auth login --hostname github.com --git-protocol https --web
}

# Count logged-in accounts
ACCOUNT_COUNT=$(echo "$GH_STATUS" | grep -c "Logged in to" || true)

if [ "$ACCOUNT_COUNT" -lt 2 ]; then
    warn "Found $ACCOUNT_COUNT gh account(s). Smithy needs 2 bot accounts (smithy + architect)."

    if [ "$ACCOUNT_COUNT" -lt 1 ]; then
        ensure_gh_login "smithy"
    fi

    echo ""
    prompt "Do you want to log in a second bot account now? (y/n)"
    read -rp "    > " ADD_SECOND
    if [[ "$ADD_SECOND" =~ ^[Yy] ]]; then
        ensure_gh_login "architect"
    else
        warn "Continuing with one account — architect features will use the same token."
    fi
else
    echo "$GH_STATUS" | grep "Logged in to" | sed 's/^/    ✓ /'
fi

echo ""

# ── Step 3: Tokens + .env (setup.py) ─────────────────────────
info "Setting up tokens and .env..."
python3 scripts/github/setup.py --env "$ENV_FILE"
echo ""

# ── Step 4: CLAUDE_CODE_OAUTH_TOKEN ──────────────────────────
source_env() {
    if [ -f "$ENV_FILE" ]; then
        set -a; source "$ENV_FILE"; set +a
    fi
}
source_env

if [ -z "${CLAUDE_CODE_OAUTH_TOKEN:-}" ]; then
    info "Claude OAuth token not found in .env."
    echo "    Run \`claude setup-token\` in another terminal to get your token."
    echo ""
    prompt "Paste your CLAUDE_CODE_OAUTH_TOKEN:"
    read -rp "    > " CLAUDE_TOKEN
    if [ -z "$CLAUDE_TOKEN" ]; then
        error "CLAUDE_CODE_OAUTH_TOKEN is required."
    fi
    echo "CLAUDE_CODE_OAUTH_TOKEN=$CLAUDE_TOKEN" >> "$ENV_FILE"
    export CLAUDE_CODE_OAUTH_TOKEN="$CLAUDE_TOKEN"
    echo "    ✓ Token saved to .env"
else
    echo "    ✓ CLAUDE_CODE_OAUTH_TOKEN already set"
fi
echo ""

# ── Step 5: Which repo? ───────────────────────────────────────
info "Which repository should Smithy work on?"
prompt "Repository (owner/repo):"
read -rp "    > " TARGET_REPO

if [ -z "$TARGET_REPO" ] || [[ "$TARGET_REPO" != *"/"* ]]; then
    error "Repository must be in owner/repo format."
fi
echo ""

# ── Step 6: Start ngrok ───────────────────────────────────────
info "Starting ngrok tunnel on port 8080..."

NGROK_URL=""

# Check if ngrok is already running
if curl -s http://localhost:4040/api/tunnels &>/dev/null; then
    NGROK_URL=$(curl -s http://localhost:4040/api/tunnels \
        | python3 -c "import sys,json; tunnels=json.load(sys.stdin)['tunnels']; \
          print(next((t['public_url'] for t in tunnels if t['public_url'].startswith('https')), ''))" 2>/dev/null || true)
fi

if [ -z "$NGROK_URL" ]; then
    # Start ngrok in background
    ngrok start smithy \
        --config "$HOME/Library/Application Support/ngrok/ngrok.yml" \
        --log /tmp/ngrok-smithy.log &
    NGROK_PID=$!
    echo "    Started ngrok (pid $NGROK_PID), waiting for tunnel..."

    # Wait up to 10 seconds for the tunnel URL
    for i in $(seq 1 20); do
        sleep 0.5
        NGROK_URL=$(curl -s http://localhost:4040/api/tunnels 2>/dev/null \
            | python3 -c "import sys,json; d=json.load(sys.stdin); \
              tunnels=d.get('tunnels',[]); \
              print(next((t['public_url'] for t in tunnels if t['public_url'].startswith('https')), ''))" 2>/dev/null || true)
        [ -n "$NGROK_URL" ] && break
    done
fi

if [ -z "$NGROK_URL" ]; then
    warn "Could not detect ngrok URL automatically."
    prompt "Enter your ngrok public URL (e.g. https://abc123.ngrok-free.app):"
    read -rp "    > " NGROK_URL
fi

echo "    ✓ Tunnel URL: $NGROK_URL"
echo ""

# ── Step 7: Configure the repo ───────────────────────────────
info "Configuring repository $TARGET_REPO..."
python3 scripts/github/setup_repo.py "$TARGET_REPO" \
    --env "$ENV_FILE" \
    --orchestrator-url "$NGROK_URL"
echo ""

# ── Step 8: Start the orchestrator ───────────────────────────
info "Starting the orchestrator..."

docker network create smithy-net 2>/dev/null && echo "    ✓ Created docker network smithy-net" \
    || echo "    ✓ Docker network smithy-net already exists"

docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" pull --quiet
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" up -d

echo ""

# Wait for health check
echo "    Waiting for orchestrator to be ready..."
for i in $(seq 1 20); do
    sleep 1
    if curl -sf http://localhost:8080/api/health &>/dev/null; then
        echo "    ✓ Orchestrator is up"
        break
    fi
    if [ "$i" -eq 20 ]; then
        warn "Orchestrator did not respond in time. Check logs:"
        echo "      docker compose -f $COMPOSE_FILE logs"
    fi
done

# ── Done ──────────────────────────────────────────────────────
echo ""
echo -e "${BOLD}╔══════════════════════════════════════════════════╗${RESET}"
echo -e "${BOLD}║   Smithy-AI is running!                          ║${RESET}"
echo -e "${BOLD}╚══════════════════════════════════════════════════╝${RESET}"
echo ""
echo "  Orchestrator : http://localhost:8080"
echo "  Public URL   : $NGROK_URL"
echo "  Repository   : $TARGET_REPO"
echo ""
echo "  Next: open a GitHub issue on $TARGET_REPO,"
echo "        assign it to @${SMITHY_BOT_USER:-smithy-bot}, and watch Smithy work."
echo ""
echo "  Logs: docker compose -f examples/github-local/docker-compose.yml logs -f"
echo ""
