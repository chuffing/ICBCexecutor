package com.danuvius.agent.metric.model;

import lombok.Data;

import java.util.Map;

/** 采集到并上报给平台的Redis与Sentinel运行数据。 */
@Data
public class MetricData {
    /** CPU占用率（%）。 */ private String cpuPer;
    /** 服务器内存占用率（%）。 */ private String memServerPer;
    /** Redis内存使用量（字节）。 */ private String memUs;
    /** 命令加权平均瞬时延迟（微秒）。 */ private String cmdLatency;
    /** 网络输入带宽（kbps）。 */ private String ioIik;
    /** 网络输出带宽（kbps）。 */ private String ioIok;
    /** 瞬时QPS。 */ private String ops;
    /** Redis节点角色（master/slave）。 */ private String role;
    /** Redis版本。 */ private String redisVersion;
    /** Redis节点运行ID。 */ private String runId;
    /** Redis主节点IP。 */ private String masterHost;
    /** Redis主节点端口。 */ private String masterPort;
    /** 已连接副本数。 */ private String connectedSlaves;
    /** 主从复制延迟（秒）。 */ private String replDelay;
    /** 本次统一采集时间戳（毫秒）。 */ private Long collectTime;
    /** Redis状态（up/down）。 */ private String status;
    /** Redis采集失败原因。 */ private String errorMsg;
    /** Sentinel状态（up/down）。 */ private String sentinelStatus;
    /** Sentinel配置的主节点名称。 */ private String sentinelMasterName;
    /** Sentinel当前记录的主节点IP。 */ private String sentinelMasterHost;
    /** Sentinel当前记录的主节点端口。 */ private Integer sentinelMasterPort;
    /** Sentinel检查失败原因。 */ private String sentinelErrorMsg;
    /** 扩展信息。 */ private Map<String, String> extra;
}