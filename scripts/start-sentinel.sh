#!/bin/bash
set -euo pipefail

ENV_FILE=${AGENT_ENV_FILE:-/etc/redis-monitor-agent/agent.env}
if [ -f "$ENV_FILE" ]; then
    # 环境文件必须由root维护，平台任务不能修改它。
    source "$ENV_FILE"
fi

SERVICE=${AGENT_SENTINEL_SERVICE:-redis-sentinel.service}
SENTINEL_PORT=${AGENT_SENTINEL_PORT:-26379}
MASTER_NAME=${AGENT_SENTINEL_MASTER_NAME:-mymaster}
REDIS_CLI=${AGENT_REDIS_CLI:-${AGENT_REDIS_CURRENT_LINK:-/opt/redis/current}/bin/redis-cli}
if [ -n "${AGENT_SENTINEL_PASSWORD:-}" ]; then
    export REDISCLI_AUTH="$AGENT_SENTINEL_PASSWORD"
fi

systemctl start "$SERVICE"

for _ in $(seq 1 10); do
    if systemctl is-active --quiet "$SERVICE" \
        && ss -lnt | grep -q ":${SENTINEL_PORT}" \
        && [ "$($REDIS_CLI -p "$SENTINEL_PORT" ping 2>/dev/null)" = "PONG" ]; then
        master_info=$($REDIS_CLI --raw -p "$SENTINEL_PORT" SENTINEL master "$MASTER_NAME" 2>/dev/null || true)
        if printf '%s\n' "$master_info" | grep -qx 'master' \
            && ! printf '%s\n' "$master_info" | grep -qx 'master,disconnected'; then
            exit 0
        fi
    fi
    sleep 1
done

echo "Sentinel did not become healthy within 10 seconds" >&2
systemctl status "$SERVICE" --no-pager >&2 || true
exit 1
