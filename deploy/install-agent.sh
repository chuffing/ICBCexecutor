#!/bin/bash
set -Eeuo pipefail

APP_DIR=/opt/redis-monitor-agent
CURRENT_JAR="${APP_DIR}/redis-monitor-agent.jar"
SCRIPTS_DIR="${APP_DIR}/scripts"
STATE_DIR="${APP_DIR}/state"
BACKUP_DIR="${APP_DIR}/deploy-backup"
ENV_DIR=/etc/redis-monitor-agent
ENV_FILE="${ENV_DIR}/agent.env"
UNIT_FILE=/etc/systemd/system/redis-monitor-agent.service

NEW_JAR=/tmp/redis-monitor-agent.jar.new
NEW_INSTALLER=/tmp/install-redis-monitor-agent.sh
NEW_UNIT=/tmp/redis-monitor-agent.service.new
OVERRIDES_FILE=/tmp/redis-monitor-agent.env.overrides
STATUS_FILE=/tmp/redis-monitor-agent-deploy-status.txt

SCRIPT_NAMES=(
  start-redis.sh
  stop-redis.sh
  start-sentinel.sh
  stop-sentinel.sh
  restore-config.sh
)

ROLLBACK_REQUIRED=false
JAR_EXISTED=false
ENV_EXISTED=false
UNIT_EXISTED=false
SCRIPTS_EXISTED=false

exec >"${STATUS_FILE}" 2>&1

# 把 KEY=VALUE 行写入环境文件；已存在的同名键会被替换。
upsert_env_line() {
  local key="$1"
  local line="$2"
  local temporary
  temporary="$(mktemp "${ENV_DIR}/agent.env.XXXXXX")"
  if [[ -f "${ENV_FILE}" ]]; then
    grep -v "^${key}=" "${ENV_FILE}" > "${temporary}" || true
  fi
  printf '%s\n' "${line}" >> "${temporary}"
  install -o root -g root -m 0600 "${temporary}" "${ENV_FILE}"
  rm -f "${temporary}"
}

# 环境文件缺少某个键时写入默认值，已有生产配置保持不变。
ensure_env_default() {
  local key="$1"
  local line="$2"
  if ! grep -q "^${key}=" "${ENV_FILE}" 2>/dev/null; then
    upsert_env_line "${key}" "${line}"
  fi
}

# 合并 PowerShell 本次明确传入的配置覆盖项。
merge_environment_overrides() {
  local line
  local key
  [[ -f "${OVERRIDES_FILE}" ]] || return 0
  while IFS= read -r line || [[ -n "${line}" ]]; do
    # 防御性移除Windows CRLF中的CR，避免它进入Linux环境变量值。
    line="${line%$'\r'}"
    [[ -z "${line}" ]] && continue
    key="${line%%=*}"
    if [[ ! "${key}" =~ ^[A-Z][A-Z0-9_]*$ ]]; then
      echo "环境变量名称不合法: ${key}"
      return 1
    fi
    upsert_env_line "${key}" "${line}"
  done < "${OVERRIDES_FILE}"
}

# 备份部署会修改的文件，供启动失败时恢复。
backup_current_installation() {
  install -d -o root -g root -m 0700 "${BACKUP_DIR}"
  rm -rf "${BACKUP_DIR}/scripts"

  if [[ -f "${CURRENT_JAR}" ]]; then
    JAR_EXISTED=true
    cp -p "${CURRENT_JAR}" "${BACKUP_DIR}/redis-monitor-agent.jar"
  fi
  if [[ -f "${ENV_FILE}" ]]; then
    ENV_EXISTED=true
    cp -p "${ENV_FILE}" "${BACKUP_DIR}/agent.env"
  fi
  if [[ -f "${UNIT_FILE}" ]]; then
    UNIT_EXISTED=true
    cp -p "${UNIT_FILE}" "${BACKUP_DIR}/redis-monitor-agent.service"
  fi
  if [[ -d "${SCRIPTS_DIR}" ]]; then
    SCRIPTS_EXISTED=true
    cp -a "${SCRIPTS_DIR}" "${BACKUP_DIR}/scripts"
  fi
}

# Agent 新版本无法启动时恢复 JAR、脚本、环境文件和 systemd 单元。
rollback() {
  local original_exit="$1"
  trap - ERR
  set +e
  echo "部署失败，开始恢复执行器部署前状态……"
  systemctl stop redis-monitor-agent.service >/dev/null 2>&1 || true

  if [[ "${JAR_EXISTED}" == true ]]; then
    install -o root -g root -m 0644 "${BACKUP_DIR}/redis-monitor-agent.jar" "${CURRENT_JAR}"
  else
    rm -f "${CURRENT_JAR}"
  fi
  if [[ "${ENV_EXISTED}" == true ]]; then
    install -o root -g root -m 0600 "${BACKUP_DIR}/agent.env" "${ENV_FILE}"
  else
    rm -f "${ENV_FILE}"
  fi
  if [[ "${UNIT_EXISTED}" == true ]]; then
    install -o root -g root -m 0644 "${BACKUP_DIR}/redis-monitor-agent.service" "${UNIT_FILE}"
  else
    rm -f "${UNIT_FILE}"
  fi
  rm -rf "${SCRIPTS_DIR}"
  if [[ "${SCRIPTS_EXISTED}" == true ]]; then
    cp -a "${BACKUP_DIR}/scripts" "${SCRIPTS_DIR}"
  else
    install -d -o root -g root -m 0755 "${SCRIPTS_DIR}"
  fi

  systemctl daemon-reload
  if [[ "${UNIT_EXISTED}" == true && "${JAR_EXISTED}" == true ]]; then
    systemctl start redis-monitor-agent.service || true
  fi
  echo "已完成回滚，原始退出码: ${original_exit}"
  exit "${original_exit}"
}

# 任一未处理错误都会进入统一回滚，避免留下半套部署文件。
handle_error() {
  local exit_code=$?
  if [[ "${ROLLBACK_REQUIRED}" == true ]]; then
    rollback "${exit_code}"
  fi
  exit "${exit_code}"
}
trap handle_error ERR

echo "[1/8] 检查部署输入和脚本语法"
[[ "$(id -u)" -eq 0 ]]
command -v /usr/bin/java >/dev/null
command -v systemctl >/dev/null
command -v ss >/dev/null
[[ -s "${NEW_JAR}" ]]
[[ -s "${NEW_UNIT}" ]]
for script_name in "${SCRIPT_NAMES[@]}"; do
  bash -n "/tmp/${script_name}.new"
done

echo "[2/8] 备份当前执行器"
install -d -o root -g root -m 0755 "${APP_DIR}" "${ENV_DIR}"
backup_current_installation
ROLLBACK_REQUIRED=true

echo "[3/8] 生成并合并 agent.env"
touch "${ENV_FILE}"
chmod 0600 "${ENV_FILE}"
ensure_env_default AGENT_PLATFORM_URL 'AGENT_PLATFORM_URL="http://localhost:8100"'
ensure_env_default AGENT_REGISTER_CODE 'AGENT_REGISTER_CODE=""'
ensure_env_default AGENT_NAME "AGENT_NAME=\"$(hostname)\""
ensure_env_default AGENT_SERVER_PORT 'AGENT_SERVER_PORT="9101"'
ensure_env_default AGENT_ROOT 'AGENT_ROOT="/opt/redis-monitor-agent"'
ensure_env_default AGENT_SCRIPT_DIR 'AGENT_SCRIPT_DIR="/opt/redis-monitor-agent/scripts"'
ensure_env_default AGENT_STATE_DIR 'AGENT_STATE_DIR="/opt/redis-monitor-agent/state"'
ensure_env_default AGENT_TOKEN_FILE 'AGENT_TOKEN_FILE="/opt/redis-monitor-agent/agent-token.dat"'
ensure_env_default AGENT_UPGRADE_DIR 'AGENT_UPGRADE_DIR="/tmp/redis-upgrade"'
ensure_env_default AGENT_VERSION_PACKAGE_MAX_BYTES 'AGENT_VERSION_PACKAGE_MAX_BYTES="536870912"'
ensure_env_default AGENT_CONFIG_FILE_MAX_BYTES 'AGENT_CONFIG_FILE_MAX_BYTES="10485760"'
ensure_env_default AGENT_REDIS_ROOT 'AGENT_REDIS_ROOT="/opt/redis"'
ensure_env_default AGENT_REDIS_RELEASES_DIR 'AGENT_REDIS_RELEASES_DIR="/opt/redis/releases"'
ensure_env_default AGENT_REDIS_CURRENT_LINK 'AGENT_REDIS_CURRENT_LINK="/opt/redis/current"'
ensure_env_default AGENT_REDIS_CONFIG_DIR 'AGENT_REDIS_CONFIG_DIR="/etc/redis"'
ensure_env_default AGENT_REDIS_DATA_DIR 'AGENT_REDIS_DATA_DIR="/opt/redis/data"'
ensure_env_default AGENT_REDIS_SERVICE 'AGENT_REDIS_SERVICE="redis-server.service"'
ensure_env_default AGENT_REDIS_HOST 'AGENT_REDIS_HOST="127.0.0.1"'
ensure_env_default AGENT_REDIS_PORT 'AGENT_REDIS_PORT="6379"'
ensure_env_default AGENT_REDIS_PASSWORD 'AGENT_REDIS_PASSWORD=""'
ensure_env_default AGENT_REDIS_DB 'AGENT_REDIS_DB="0"'
ensure_env_default AGENT_REDIS_TIMEOUT 'AGENT_REDIS_TIMEOUT="2000"'
ensure_env_default AGENT_SENTINEL_SERVICE 'AGENT_SENTINEL_SERVICE="redis-sentinel.service"'
ensure_env_default AGENT_SENTINEL_HOST 'AGENT_SENTINEL_HOST="127.0.0.1"'
ensure_env_default AGENT_SENTINEL_PORT 'AGENT_SENTINEL_PORT="26379"'
ensure_env_default AGENT_SENTINEL_PASSWORD 'AGENT_SENTINEL_PASSWORD=""'
ensure_env_default AGENT_SENTINEL_MASTER_NAME 'AGENT_SENTINEL_MASTER_NAME="mymaster"'
ensure_env_default AGENT_SENTINEL_TIMEOUT 'AGENT_SENTINEL_TIMEOUT="2000"'
ensure_env_default AGENT_RECOVERY_REDIS_BACKUP_FILE 'AGENT_RECOVERY_REDIS_BACKUP_FILE="/backup/redis-config/redis.conf.bak"'
ensure_env_default AGENT_RECOVERY_SENTINEL_BACKUP_FILE 'AGENT_RECOVERY_SENTINEL_BACKUP_FILE="/backup/redis-config/sentinel.conf.bak"'
ensure_env_default AGENT_RECOVERY_REDIS_TARGET_FILE 'AGENT_RECOVERY_REDIS_TARGET_FILE="/etc/redis/redis.conf"'
ensure_env_default AGENT_RECOVERY_SENTINEL_TARGET_FILE 'AGENT_RECOVERY_SENTINEL_TARGET_FILE="/etc/redis/sentinel.conf"'
ensure_env_default AGENT_COLLECT_INTERVAL 'AGENT_COLLECT_INTERVAL="300000"'
ensure_env_default AGENT_HEARTBEAT_INTERVAL 'AGENT_HEARTBEAT_INTERVAL="30000"'
merge_environment_overrides

# shellcheck disable=SC1090
source "${ENV_FILE}"

echo "[4/8] 创建服务器运行目录"
install -d -o root -g root -m 0755 \
  "${AGENT_ROOT}" "${AGENT_SCRIPT_DIR}" "${AGENT_STATE_DIR}" \
  "${AGENT_UPGRADE_DIR}" "${AGENT_UPGRADE_DIR}/incoming" \
  "${AGENT_UPGRADE_DIR}/config-incoming" \
  "${AGENT_REDIS_ROOT}" "${AGENT_REDIS_RELEASES_DIR}" \
  "${AGENT_REDIS_CONFIG_DIR}" "${AGENT_REDIS_DATA_DIR}"
install -d -o root -g root -m 0755 \
  "$(dirname "${AGENT_RECOVERY_REDIS_BACKUP_FILE}")" \
  "$(dirname "${AGENT_RECOVERY_SENTINEL_BACKUP_FILE}")" \
  "$(dirname "${AGENT_RECOVERY_REDIS_TARGET_FILE}")" \
  "$(dirname "${AGENT_RECOVERY_SENTINEL_TARGET_FILE}")"

echo "[5/8] 停止并安装执行器"
systemctl stop redis-monitor-agent.service >/dev/null 2>&1 || true
install -o root -g root -m 0644 "${NEW_JAR}" "${CURRENT_JAR}"
for script_name in "${SCRIPT_NAMES[@]}"; do
  install -o root -g root -m 0755 "/tmp/${script_name}.new" "${SCRIPTS_DIR}/${script_name}"
done
install -o root -g root -m 0644 "${NEW_UNIT}" "${UNIT_FILE}"

echo "[6/8] 注册开机自启并启动 Agent"
systemctl daemon-reload
systemctl enable redis-monitor-agent.service >/dev/null
systemctl start redis-monitor-agent.service

echo "[7/8] 等待 Agent 端口"
ready=false
for _ in $(seq 1 30); do
  if systemctl is-active --quiet redis-monitor-agent.service \
    && ss -lnt | awk -v port="${AGENT_SERVER_PORT}" \
      '$4 ~ (":" port "$") {found=1} END {exit found ? 0 : 1}'; then
    ready=true
    break
  fi
  sleep 1
done
if [[ "${ready}" != true ]]; then
  echo "Agent未在30秒内监听端口 ${AGENT_SERVER_PORT}"
  journalctl -u redis-monitor-agent.service --no-pager -n 80 || true
  false
fi

echo "[8/8] 输出部署验收结果"
echo "deployment=SUCCESS"
echo "agent_name=${AGENT_NAME}"
echo "service=$(systemctl is-active redis-monitor-agent.service)"
echo "enabled=$(systemctl is-enabled redis-monitor-agent.service)"
echo "pid=$(systemctl show redis-monitor-agent.service -p MainPID --value)"
echo "guest_ip=$(hostname -I | awk '{print $1}')"
echo "agent_port=${AGENT_SERVER_PORT}"
echo "redis_config_dir=${AGENT_REDIS_CONFIG_DIR}"
echo "redis_data_dir=${AGENT_REDIS_DATA_DIR}"
echo "redis_service=$(systemctl is-active "${AGENT_REDIS_SERVICE}" 2>/dev/null || true)"
echo "sentinel_service=$(systemctl is-active "${AGENT_SENTINEL_SERVICE}" 2>/dev/null || true)"
if [[ -s "${AGENT_TOKEN_FILE}" ]]; then
  echo "token=EXISTS"
else
  echo "token=MISSING"
fi

ROLLBACK_REQUIRED=false
