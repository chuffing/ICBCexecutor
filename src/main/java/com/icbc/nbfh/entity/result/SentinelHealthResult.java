package com.icbc.nbfh.entity.result;

import lombok.Getter;

/**
 * 单次Sentinel健康检查结果。
 */
@Getter
public class SentinelHealthResult {

    /** Sentinel是否可访问并且正在正常监控指定MASTER。 */
    private final boolean healthy;

    /** Sentinel配置中的MASTER名称；检查失败时为空。 */
    private final String masterName;

    /** Sentinel当前记录的MASTER地址；检查失败时为空。 */
    private final String masterHost;

    /** Sentinel当前记录的MASTER端口；检查失败时为空。 */
    private final Integer masterPort;

    /** 不包含密码的检查说明。 */
    private final String message;

    private SentinelHealthResult(boolean healthy, String masterName, String masterHost,
                                 Integer masterPort, String message) {
        this.healthy = healthy;
        this.masterName = masterName;
        this.masterHost = masterHost;
        this.masterPort = masterPort;
        this.message = message;
    }

    /** 创建健康结果。 */
    public static SentinelHealthResult healthy(String masterName, String masterHost,
                                                int masterPort) {
        return new SentinelHealthResult(
                true,
                masterName,
                masterHost,
                masterPort,
                "Sentinel健康检查通过"
        );
    }

    /** 创建不健康结果。 */
    public static SentinelHealthResult unhealthy(String message) {
        return new SentinelHealthResult(false, null, null, null, message);
    }
}
