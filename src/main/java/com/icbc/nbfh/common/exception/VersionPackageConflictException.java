package com.icbc.nbfh.common.exception;

/**
 * 同一任务编号已经对应另一个版本包。
 */
public class VersionPackageConflictException extends RuntimeException {

    /**
     * 创建版本包冲突异常，由控制器转换为 HTTP 409。
     *
     * @param message 不包含敏感信息的冲突原因
     */
    public VersionPackageConflictException(String message) {
        super(message);
    }
}
