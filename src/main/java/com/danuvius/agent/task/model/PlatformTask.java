package com.danuvius.agent.task.model;

import com.danuvius.agent.common.operation.OperationType;
import lombok.Data;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 平台下发的任务
 */
@Data
public class PlatformTask {

    private static final Pattern TASK_NO_PATTERN = Pattern.compile("TASK\\d{12}");

    /** 任务编号，例如 TASK202609230001。 */
    private String taskNo;

    /** 当前操作在业务任务中的步骤序号。 */
    private Integer stepNo;

    /** 执行器白名单中的本机操作类型。 */
    private String taskType;

    /** 当前操作需要的结构化参数，不允许包含任意Shell命令。 */
    private Map<String, String> params;

    /**
     * 将平台传入的任务类型转换为执行器允许的固定操作类型。
     *
     * @return 校验后的固定操作类型
     * @throws IllegalArgumentException 任务类型为空或不在白名单中
     */
    public OperationType resolveOperationType() {
        return OperationType.fromValue(taskType);
    }

    /**
     * 取得并校验平台下发的业务任务编号。
     *
     * @return 去除首尾空格后的任务编号
     * @throws IllegalArgumentException 任务编号为空或格式不正确
     */
    public String requireTaskNo() {
        if (taskNo == null || taskNo.trim().isEmpty()) {
            throw new IllegalArgumentException("任务编号不能为空");
        }

        String normalizedTaskNo = taskNo.trim();
        if (!TASK_NO_PATTERN.matcher(normalizedTaskNo).matches()) {
            throw new IllegalArgumentException("任务编号格式不正确: " + normalizedTaskNo);
        }
        return normalizedTaskNo;
    }

    /**
     * 取得并校验平台下发的步骤序号。
     *
     * @return 大于零的步骤序号
     * @throws IllegalArgumentException 步骤序号为空或小于 1
     */
    public int requireStepNo() {
        if (stepNo == null || stepNo < 1) {
            throw new IllegalArgumentException("stepNo 必须大于 0");
        }
        return stepNo;
    }
}
