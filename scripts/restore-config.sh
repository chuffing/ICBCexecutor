#!/bin/bash
set -euo pipefail

ENV_FILE=${AGENT_ENV_FILE:-/etc/redis-monitor-agent/agent.env}
if [ -f "$ENV_FILE" ]; then
    # 环境文件必须由root维护，平台任务不能修改它。
    source "$ENV_FILE"
fi

REDIS_BACKUP=${AGENT_RECOVERY_REDIS_BACKUP_FILE:-/backup/redis-config/redis.conf.bak}
SENTINEL_BACKUP=${AGENT_RECOVERY_SENTINEL_BACKUP_FILE:-/backup/redis-config/sentinel.conf.bak}
REDIS_TARGET=${AGENT_RECOVERY_REDIS_TARGET_FILE:-/etc/redis/redis.conf}
SENTINEL_TARGET=${AGENT_RECOVERY_SENTINEL_TARGET_FILE:-/etc/redis/sentinel.conf}
REDIS_SERVICE=${AGENT_REDIS_SERVICE:-redis-server.service}
SENTINEL_SERVICE=${AGENT_SENTINEL_SERVICE:-redis-sentinel.service}
REDIS_PORT=${AGENT_REDIS_PORT:-6379}
SENTINEL_PORT=${AGENT_SENTINEL_PORT:-26379}
REDIS_TARGET_DIR=$(dirname "$REDIS_TARGET")
SENTINEL_TARGET_DIR=$(dirname "$SENTINEL_TARGET")
WORK_DIR=$(mktemp -d "${TMPDIR:-/tmp}/restore-config.XXXXXX")
REDIS_TEMP=""
SENTINEL_TEMP=""
REDIS_TARGET_EXISTED=false
SENTINEL_TARGET_EXISTED=false
RESTORE_COMPLETED=false

cleanup() {
    if [ "$RESTORE_COMPLETED" != "true" ]; then
        if [ "$REDIS_TARGET_EXISTED" = "true" ] && [ -f "$WORK_DIR/redis.conf.original" ]; then
            install -o root -g root -m 0644 "$WORK_DIR/redis.conf.original" "$REDIS_TARGET" || true
        else
            rm -f "$REDIS_TARGET"
        fi
        if [ "$SENTINEL_TARGET_EXISTED" = "true" ] && [ -f "$WORK_DIR/sentinel.conf.original" ]; then
            install -o root -g root -m 0644 "$WORK_DIR/sentinel.conf.original" "$SENTINEL_TARGET" || true
        else
            rm -f "$SENTINEL_TARGET"
        fi
    fi
    [ -z "$REDIS_TEMP" ] || rm -f "$REDIS_TEMP"
    [ -z "$SENTINEL_TEMP" ] || rm -f "$SENTINEL_TEMP"
    rm -rf "$WORK_DIR"
}
trap cleanup EXIT

# 平台负责全局停止顺序，脚本仍在本机复核，避免运行中的服务读取到一半新一半旧的配置。
if systemctl is-active --quiet "$REDIS_SERVICE" \
    || systemctl is-active --quiet "$SENTINEL_SERVICE"; then
    echo "Redis and Sentinel must be stopped before restoring configuration" >&2
    exit 1
fi
if ss -lnt | grep -q ":${REDIS_PORT}" \
    || ss -lnt | grep -q ":${SENTINEL_PORT}"; then
    echo "Redis or Sentinel port is still listening" >&2
    exit 1
fi

test -f "$REDIS_BACKUP"
test -r "$REDIS_BACKUP"
test -f "$SENTINEL_BACKUP"
test -r "$SENTINEL_BACKUP"
test -d "$REDIS_TARGET_DIR"
test -d "$SENTINEL_TARGET_DIR"

if [ -f "$REDIS_TARGET" ]; then
    REDIS_TARGET_EXISTED=true
    cp -p "$REDIS_TARGET" "$WORK_DIR/redis.conf.original"
fi
if [ -f "$SENTINEL_TARGET" ]; then
    SENTINEL_TARGET_EXISTED=true
    cp -p "$SENTINEL_TARGET" "$WORK_DIR/sentinel.conf.original"
fi

# 临时文件与目标文件位于同一目录，最终mv不会跨文件系统，可避免只写入一半。
REDIS_TEMP=$(mktemp "$REDIS_TARGET_DIR/.redis-config.restore.XXXXXX")
SENTINEL_TEMP=$(mktemp "$SENTINEL_TARGET_DIR/.sentinel-config.restore.XXXXXX")
install -o root -g root -m 0644 "$REDIS_BACKUP" "$REDIS_TEMP"
install -o root -g root -m 0644 "$SENTINEL_BACKUP" "$SENTINEL_TEMP"
mv -f "$REDIS_TEMP" "$REDIS_TARGET"
REDIS_TEMP=""
mv -f "$SENTINEL_TEMP" "$SENTINEL_TARGET"
SENTINEL_TEMP=""

RESTORE_COMPLETED=true
