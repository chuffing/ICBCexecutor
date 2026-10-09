package com.danuvius.agent.common.operation;

/**
 * 执行器允许执行的固定运维操作类型。
 *
 * 平台只能下发这里定义的操作，执行器不接受任意 Shell 命令。
 */
public enum OperationType {

    /** 检查新服务器是否满足部署Redis的基础条件，不修改服务器状态。 */
    PRECHECK_HOST,

    /** 启动本机 Redis，并在启动后进行健康检查。 */
    START_REDIS,

    /** 停止本机 Redis，并确认进程和监听端口已经关闭。 */
    STOP_REDIS,

    /** 启动本机 Sentinel，并验证监控的 MASTER。 */
    START_SENTINEL,

    /** 停止本机 Sentinel，并确认进程和监听端口已经关闭。 */
    STOP_SENTINEL,

    /** 使用人工确认的配置备份恢复 Redis 和 Sentinel 配置。 */
    RESTORE_CONFIG,

    /** 把平台审核并上传的 Redis 和 Sentinel 配置部署到本机。 */
    DEPLOY_CONFIG,

    /** 校验并解压 Redis 版本包，但不切换当前运行版本。 */
    DEPLOY_VERSION,

    /** 将本机 Redis current 链接切换到已经部署的新版本。 */
    SWITCH_VERSION,

    /** 将本机 Redis current 链接恢复到升级前的版本。 */
    ROLLBACK_VERSION;

    /**
     * 将平台传入的任务类型字符串转换为固定操作类型。
     *
     * @param value 平台传入的任务类型
     * @return 对应的固定操作类型
     * @throws IllegalArgumentException 任务类型为空或不在白名单中
     */
    public static OperationType fromValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("任务类型不能为空");
        }

        String normalizedValue = value.trim();
        try {
            return OperationType.valueOf(normalizedValue);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的任务类型: " + normalizedValue, e);
        }
    }
}
