#!/usr/bin/env bash
# agent_heartbeat.sh — MiniDB-Lab Agent Progress Monitor
# Usage: bash tools/agent_heartbeat.sh [timeout_minutes] [--watch]
#   timeout_minutes: max idle time before alert (default 10)
#   --watch: continuous monitoring mode
#
# Checks if the agent is making progress by monitoring:
#   1. Git commit count on current branch
#   2. File modification timestamps in src/
#   3. Maven build status

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TIMEOUT_MIN="${1:-10}"
WATCH_MODE="${2:-}"

red() { echo -e "\033[31m$*\033[0m"; }
green() { echo -e "\033[32m$*\033[0m"; }
yellow() { echo -e "\033[33m$*\033[0m"; }

check_progress() {
    local now
    now=$(date +%s)
    local stale=true
    local evidence=""

    # Check 1: git commits in last N minutes
    local last_commit_ts
    last_commit_ts=$(git -C "$ROOT" log -1 --format=%ct 2>/dev/null || echo 0)
    local commit_age=$(( (now - last_commit_ts) / 60 ))
    if [ "$commit_age" -lt "$TIMEOUT_MIN" ]; then
        green "  [OK] Last commit: ${commit_age}min ago"
        stale=false
    else
        yellow "  [WARN] Last commit: ${commit_age}min ago (threshold: ${TIMEOUT_MIN}min)"
    fi

    # Check 2: File changes in src/ in last N minutes
    local recent_changes
    recent_changes=$(find "$ROOT" -path "*/src/*" -name "*.java" -newermt "${TIMEOUT_MIN} min ago" 2>/dev/null | wc -l)
    if [ "$recent_changes" -gt 0 ]; then
        green "  [OK] $recent_changes source files modified in last ${TIMEOUT_MIN}min"
        stale=false
    else
        yellow "  [WARN] No source files modified in last ${TIMEOUT_MIN}min"
    fi

    # Check 3: Maven target freshness
    local target_age=999
    for t in "$ROOT"/*/target/classes; do
        if [ -d "$t" ]; then
            local ta
            ta=$(( (now - $(stat -c %Y "$t" 2>/dev/null || echo 0)) / 60 ))
            [ "$ta" -lt "$target_age" ] && target_age=$ta
        fi
    done
    if [ "$target_age" -lt "$TIMEOUT_MIN" ]; then
        green "  [OK] Build artifacts: ${target_age}min old"
        stale=false
    else
        yellow "  [WARN] Build artifacts: ${target_age}min old"
    fi

    if $stale; then
        red "  [ALERT] Agent may be stuck — no progress detected in ${TIMEOUT_MIN}min"
        evidence="stale"
    else
        green "  [PASS] Agent is making progress"
        evidence="active"
    fi

    # Summary
    echo ""
    echo "--- $(date '+%H:%M:%S') | Branch: $(git -C "$ROOT" branch --show-current) | Commits: $(git -C "$ROOT" rev-list --count HEAD) | Status: $evidence ---"

    if [ "$evidence" = "stale" ]; then
        return 1
    fi
    return 0
}

echo "=== MiniDB-Lab Agent Heartbeat ==="
echo "Timeout threshold: ${TIMEOUT_MIN} min"
echo ""

if [ "$WATCH_MODE" = "--watch" ]; then
    echo "Continuous monitoring (Ctrl+C to stop)..."
    while true; do
        check_progress || true
        sleep 60
    done
else
    check_progress
fi
