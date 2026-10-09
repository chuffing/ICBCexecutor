package com.danuvius.agent.redis.model;

import java.util.Locale;
import java.util.Map;

/**
 * START_REDIS 操作允许使用的拓扑验收参数。
 *
 * <p>平台正式调度时应声明期望角色。未声明时保留兼容模式，只检查 Redis
 * 自身健康状态，便于旧调用方逐步升级。</p>
 */
public class StartRedisTaskParameters {

    public static final String ROLE_MASTER = "MASTER";
    public static final String ROLE_REPLICA = "REPLICA";

    private final String expectedRole;
    private final String expectedMasterHost;
    private final Integer expectedMasterPort;

    /**
     * 创建已经完成格式校验的启动参数。
     *
     * @param expectedRole 期望角色；兼容模式下为空
     * @param expectedMasterHost REPLICA 期望连接的主库地址
     * @param expectedMasterPort REPLICA 期望连接的主库端口
     */
    private StartRedisTaskParameters(String expectedRole, String expectedMasterHost,
                                     Integer expectedMasterPort) {
        this.expectedRole = expectedRole;
        this.expectedMasterHost = expectedMasterHost;
        this.expectedMasterPort = expectedMasterPort;
    }

    /**
     * 读取并校验平台下发的 START_REDIS 参数。
     *
     * @param params 平台任务参数
     * @return 规范化后的参数
     * @throws IllegalArgumentException 参数组合或格式不合法时抛出
     */
    public static StartRedisTaskParameters from(Map<String, String> params) {
        String rawRole = params == null ? null : trim(params.get("expectedRole"));
        if (rawRole == null) {
            if (params != null && (trim(params.get("expectedMasterHost")) != null
                    || trim(params.get("expectedMasterPort")) != null)) {
                throw new IllegalArgumentException(
                        "设置expectedMasterHost或expectedMasterPort时必须设置expectedRole"
                );
            }
            return new StartRedisTaskParameters(null, null, null);
        }

        String expectedRole = rawRole.toUpperCase(Locale.ROOT);
        String expectedMasterHost = trim(params.get("expectedMasterHost"));
        String expectedMasterPort = trim(params.get("expectedMasterPort"));

        if (ROLE_MASTER.equals(expectedRole)) {
            if (expectedMasterHost != null || expectedMasterPort != null) {
                throw new IllegalArgumentException(
                        "期望MASTER时不能设置expectedMasterHost或expectedMasterPort"
                );
            }
            return new StartRedisTaskParameters(expectedRole, null, null);
        }

        if (!ROLE_REPLICA.equals(expectedRole)) {
            throw new IllegalArgumentException("expectedRole只允许MASTER或REPLICA");
        }
        validateHost(expectedMasterHost);
        int port = parsePort(expectedMasterPort);
        return new StartRedisTaskParameters(expectedRole, expectedMasterHost, port);
    }

    /**
     * 校验期望主库地址，拒绝空值、控制字符和过长内容。
     *
     * @param host 平台传入的主库地址
     */
    private static void validateHost(String host) {
        if (host == null || host.length() > 255) {
            throw new IllegalArgumentException("expectedMasterHost不能为空且不能超过255个字符");
        }
        for (int i = 0; i < host.length(); i++) {
            char value = host.charAt(i);
            if (Character.isWhitespace(value) || Character.isISOControl(value)) {
                throw new IllegalArgumentException("expectedMasterHost不能包含空白或控制字符");
            }
        }
    }

    /**
     * 将主库端口转换为有效的 TCP 端口。
     *
     * @param rawPort 平台传入的端口文本
     * @return 1 到 65535 之间的端口
     */
    private static int parsePort(String rawPort) {
        if (rawPort == null) {
            throw new IllegalArgumentException("expectedMasterPort不能为空");
        }
        try {
            int port = Integer.parseInt(rawPort);
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("expectedMasterPort必须在1到65535之间");
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("expectedMasterPort必须是整数", e);
        }
    }

    /**
     * 去除首尾空白，并把空字符串转换为 null。
     *
     * @param value 原始参数
     * @return 规范化后的值
     */
    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    /** @return 是否声明了平台期望的 Redis 角色 */
    public boolean hasExpectedTopology() {
        return expectedRole != null;
    }

    /** @return 期望角色，兼容模式下为空 */
    public String getExpectedRole() {
        return expectedRole;
    }

    /** @return REPLICA 期望连接的主库地址 */
    public String getExpectedMasterHost() {
        return expectedMasterHost;
    }

    /** @return REPLICA 期望连接的主库端口 */
    public Integer getExpectedMasterPort() {
        return expectedMasterPort;
    }
}
