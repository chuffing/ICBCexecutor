package com.icbc.nbfh.common.exception;

/**
 * 表示同一个任务编号已经绑定了内容不同的配置文件。
 */
public class ConfigPackageConflictException extends RuntimeException {

    /**
     * @param message 可返回给平台的冲突说明
     */
    public ConfigPackageConflictException(String message) {
        super(message);
    }
}
