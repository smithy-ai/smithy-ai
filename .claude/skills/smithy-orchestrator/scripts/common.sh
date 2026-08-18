# Shared helpers for smithy-orchestrator scripts. Source, don't execute.
# Requires: glab (authenticated against the manifest's gitlab_host), jq.

set -euo pipefail

SKILL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANIFEST="${SMITHY_ORCH_MANIFEST:-$SKILL_DIR/repos.yml}"

if [[ ! -f "$MANIFEST" ]]; then
    echo "error: $MANIFEST not found — copy repos.yml.example to repos.yml and edit it" >&2
    exit 2
fi

GITLAB_HOST="$(sed -n 's/^gitlab_host:[[:space:]]*//p' "$MANIFEST" | head -1)"
SMITHY_BOT="$(sed -n 's/^smithy_bot:[[:space:]]*//p' "$MANIFEST" | head -1)"

if [[ -z "$GITLAB_HOST" || -z "$SMITHY_BOT" ]]; then
    echo "error: gitlab_host and smithy_bot must be set in $MANIFEST" >&2
    exit 2
fi

# glab api against the configured host
api() {
    glab api --hostname "$GITLAB_HOST" "$@"
}

# URL-encode one value (project paths, file paths, branch names)
encode() {
    jq -rn --arg v "$1" '$v|@uri'
}

# Resolve the smithy/<n>-* branch for an issue. Prints branch name; exits 1 if none.
smithy_branch() {
    local project="$1" issue="$2" branch
    branch="$(api "projects/$(encode "$project")/repository/branches?search=$(encode "smithy/$issue-")" |
        jq -r --arg p "smithy/$issue-" '[.[] | select(.name | startswith($p))][0].name // empty')"
    if [[ -z "$branch" ]]; then
        echo "error: no smithy/$issue-* branch in $project (has smithy picked up the issue yet?)" >&2
        return 1
    fi
    printf '%s\n' "$branch"
}
