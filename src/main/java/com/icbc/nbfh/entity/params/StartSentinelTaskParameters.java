package com.icbc.nbfh.entity.params;

import java.util.Map;

/**
 * START_SENTINEL 操作允许使用的目标主库验收参数。
 *
 * <p>旧调用方可以不传参数，此时只做基础健康检查。服务器切换必须同时传入
 * expectedMasterHost 和 expectedMasterPort，防止新 Sentinel 加入错误的同名集群。</p>
 */
public class StartSentinelTaskParameters {

    private final String expectedMasterHost;
    private final Integer expectedMasterPort;

    /** 保存已经完成格式校验的目标主库。 */
    private StartSentinelTaskParameters(String expectedMasterHost,
                                        Integer expectedMasterPort) {
        this.expectedMasterHost = expectedMasterHost;
        this.expectedMasterPort = expectedMasterPort;
    }

    /**
     * 读取并校验平台下发的 START_SENTINEL 参数。
     *
     * @param params 平台任务参数
     * @return 规范化后的参数
     */
    public static StartSentinelTaskParameters from(Map<String, String> params) {
        String host = params == null ? null : trim(params.get("expectedMasterHost"));
        String rawPort = params == null ? null : trim(params.get("expectedMasterPort"));
        if (host == null && rawPort == null) {
            return new StartSentinelTaskParameters(null, null);
        }
        if (host == null || rawPort == null) {
            throw new IllegalArgumentException(
                    "expectedMasterHost和expectedMasterPort必须同时设置");
        }
        validateHost(host);
        return new StartSentinelTaskParameters(host, parsePort(rawPort));
    }

    /** 校验主库地址不含空白、控制字符且长度合理。 */
    private static void validateHost(String host) {
        if (host.length() > 255) {
            throw new IllegalArgumentException("expectedMasterHost不能超过255个字符");
        }
        for (int index = 0; index < host.length(); index++) {
            char value = host.charAt(index);
            if (Character.isWhitespace(value) || Character.isISOControl(value)) {
                throw new IllegalArgumentException(
                        "expectedMasterHost不能包含空白或控制字符");
            }
        }
    }

    /** 把平台端口文本转换为有效 TCP 端口。 */
    private static int parsePort(String rawPort) {
        try {
            int port = Integer.parseInt(rawPort);
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException(
                        "expectedMasterPort必须在1到65535之间");
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("expectedMasterPort必须是整数", e);
        }
    }

    /** 去除首尾空白，并把空字符串转换为 null。 */
    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    /** @return 是否要求校验 Sentinel 最终监控的主库 */
    public boolean hasExpectedMaster() { return expectedMasterHost != null; }

    /** @return 期望主库地址 */
    public String getExpectedMasterHost() { return expectedMasterHost; }

    /** @return 期望主库端口 */
    public Integer getExpectedMasterPort() { return expectedMasterPort; }
}
