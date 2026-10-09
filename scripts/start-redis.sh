#!/bin/bash
set -euo pipefail

ENV_FILE=${AGENT_ENV_FILE:-/etc/redis-monitor-agent/agent.env}
if [ -f "$ENV_FILE" ]; then
    # 环境文件必须由root维护，平台任务不能修改它。
    source "$ENV_FILE"
fi

REDIS_SERVICE_NAME=${AGENT_REDIS_SERVICE:-redis-server.service}
systemctl start "$REDIS_SERVICE_NAME"
