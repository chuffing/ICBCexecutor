package com.danuvius.agent.redis.health;

import lombok.Getter;

/**
 * 单次 Redis 健康检查结果。
 */
@Getter
public class RedisHealthResult {

    /** PING、ROLE 及必要的复制检查是否全部通过。 */
    private final boolean healthy;

    /** 业务角色：MASTER 或 REPLICA；尚未取得角色时为空。 */
    private final String role;

    /** REPLICA 连接主库的状态；MASTER 或尚未取得状态时为空。 */
    private final String masterLinkStatus;

    /** REPLICA 当前连接的主库地址。 */
    private final String masterHost;

    /** REPLICA 当前连接的主库端口。 */
    private final Integer masterPort;

    /** 不包含 Redis 密码的检查说明。 */
    private final String message;

    /**
     * 创建一次完整健康检查结果。
     */
    private RedisHealthResult(boolean healthy, String role, String masterLinkStatus,
                              String masterHost, Integer masterPort, String message) {
        this.healthy = healthy;
        this.role = role;
        this.masterLinkStatus = masterLinkStatus;
        this.masterHost = masterHost;
        this.masterPort = masterPort;
        this.message = message;
    }

    /**
     * 创建健康的 MASTER 结果。
     *
     * @return MASTER 健康结果
     */
    public static RedisHealthResult healthyMaster() {
        return new RedisHealthResult(
                true, "MASTER", null, null, null, "Redis健康检查通过"
        );
    }

    /**
     * 创建健康的 REPLICA 结果。
     *
     * @param masterLinkStatus 复制连接状态
     * @param masterHost 当前主库地址
     * @param masterPort 当前主库端口
     * @return REPLICA 健康结果
     */
    public static RedisHealthResult healthyReplica(String masterLinkStatus,
                                                   String masterHost, Integer masterPort) {
        return new RedisHealthResult(
                true,
                "REPLICA",
                masterLinkStatus,
                masterHost,
                masterPort,
                "Redis健康检查通过"
        );
    }

    /**
     * 创建尚未取得 Redis 角色的不健康结果。
     *
     * @param message 检查失败原因
     * @return 不健康结果
     */
    public static RedisHealthResult unhealthy(String message) {
        return new RedisHealthResult(false, null, null, null, null, message);
    }

    /**
     * 创建 REPLICA 复制链路不健康结果。
     *
     * @param masterLinkStatus 当前复制连接状态
     * @param masterHost 当前主库地址
     * @param masterPort 当前主库端口
     * @param message 检查失败原因
     * @return 复制不健康结果
     */
    public static RedisHealthResult replicationUnhealthy(
            String masterLinkStatus, String masterHost, Integer masterPort, String message) {
        return new RedisHealthResult(
                false,
                "REPLICA",
                masterLinkStatus,
                masterHost,
                masterPort,
                message
        );
    }
}
