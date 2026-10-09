package com.danuvius.agent.heartbeat.model;

import lombok.Data;

import java.util.Map;

/**
 * 心跳请求
 */
@Data
public class HeartbeatRequest {

    /** Agent 身份 token（注册后获得） */
    private String token;

    /** Agent ID */
    private String agentId;

    /** 当前运行状态 */
    private String status;

    /** 主机名 */
    private String hostname;

    /** IP 地址 */
    private String ip;

    /** CPU 使用率 */
    private String cpuUsage;

    /** 内存使用率 */
    private String memUsage;

    /** 上次采集时间戳 */
    private Long lastCollectTime;

    /** 扩展信息 */
    private Map<String, String> extra;
}
