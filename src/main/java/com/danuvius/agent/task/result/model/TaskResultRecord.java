package com.danuvius.agent.task.result.model;

import lombok.Data;

/**
 * 执行器本地持久化的运维步骤结果。
 *
 * <p>先把结果写入本机磁盘，再尝试上报平台。reported=false 表示平台尚未确认，
 * 执行器重启后仍会继续补报。</p>
 */
@Data
public class TaskResultRecord {

    /** 平台业务任务编号。 */
    private String taskNo;

    /** 当前原子操作的步骤序号。 */
    private Integer stepNo;

    /** 完成该步骤的执行器编号。 */
    private String agentId;

    /** 固定白名单中的原子操作类型。 */
    private String operationType;

    /** 最终执行状态，只能是 SUCCESS 或 FAILED。 */
    private String status;

    /** OperationResult 序列化后的完整 JSON。 */
    private String result;

    /** 本机完成实际操作的时间戳。 */
    private long finishTime;

    /** 平台是否已经返回成功确认。 */
    private boolean reported;

    /** 最近一次成功收到平台确认的时间戳。 */
    private Long reportedTime;
}
