#!/bin/bash
set -euo pipefail

ENV_FILE=${AGENT_ENV_FILE:-/etc/redis-monitor-agent/agent.env}
if [ -f "$ENV_FILE" ]; then
    # 环境文件必须由root维护，平台任务不能修改它。
    source "$ENV_FILE"
fi

SERVICE=${AGENT_SENTINEL_SERVICE:-redis-sentinel.service}
SENTINEL_PORT=${AGENT_SENTINEL_PORT:-26379}

systemctl stop "$SERVICE"

for _ in $(seq 1 10); do
    if ! systemctl is-active --quiet "$SERVICE" \
        && ! ss -lnt | grep -q ":${SENTINEL_PORT}" \
        && ! pgrep -x redis-sentinel >/dev/null; then
        exit 0
    fi
    sleep 1
done

echo "Sentinel did not stop completely within 10 seconds" >&2
systemctl status "$SERVICE" --no-pager >&2 || true
ss -lntp | grep ":${SENTINEL_PORT}" >&2 || true
pgrep -a -x redis-sentinel >&2 || true
exit 1
