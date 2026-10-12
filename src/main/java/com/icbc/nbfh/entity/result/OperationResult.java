package com.icbc.nbfh.entity.result;

import com.icbc.nbfh.common.constant.OperationErrorCode;

import lombok.Data;

/**
 * 单个本机运维操作的统一执行结果。
 */
@Data
public class OperationResult {

    /** 操作是否成功。 */
    private boolean success;

    /** 固定错误分类；操作成功时为空。 */
    private OperationErrorCode errorCode;

    /** 不包含密码、token等敏感数据的结果说明。 */
    private String message;

    /** 操作开始时间，Unix毫秒时间戳。 */
    private long startTime;

    /** 操作结束时间，Unix毫秒时间戳。 */
    private long endTime;

    private OperationResult(boolean success, OperationErrorCode errorCode, String message,
                            long startTime, long endTime) {
        this.success = success;
        this.errorCode = errorCode;
        this.message = message;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    /**
     * 创建成功结果。
     */
    public static OperationResult success(String message, long startTime, long endTime) {
        return new OperationResult(true, null, message, startTime, endTime);
    }

    /**
     * 创建失败结果。
     */
    public static OperationResult failure(OperationErrorCode errorCode, String message,
                                          long startTime, long endTime) {
        if (errorCode == null) {
            throw new IllegalArgumentException("失败结果必须包含错误码");
        }
        return new OperationResult(false, errorCode, message, startTime, endTime);
    }
}
