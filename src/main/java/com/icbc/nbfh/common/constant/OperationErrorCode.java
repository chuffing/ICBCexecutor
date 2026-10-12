package com.icbc.nbfh.common.constant;

import com.icbc.nbfh.entity.result.OperationResult;

/**
 * 本机运维操作允许返回的固定错误分类。
 *
 * 错误码用于平台判断错误类别，具体原因由 OperationResult.message 描述。
 */
public enum OperationErrorCode {

    /** 平台下发了不允许的任务类型。 */
    INVALID_TASK_TYPE,

    /** 任务类型合法，但对应处理器尚未实现。 */
    OPERATION_NOT_IMPLEMENTED,

    /** Redis或Sentinel配置不存在或不合法。 */
    CONFIG_INVALID,

    /** 固定脚本或必须使用的程序不存在。 */
    COMMAND_NOT_FOUND,

    /** 固定脚本执行超过规定时间。 */
    COMMAND_TIMEOUT,

    /** 固定脚本返回非零退出码。 */
    COMMAND_EXECUTION_FAILED,

    /** 服务启动后在规定时间内未达到健康状态。 */
    START_TIMEOUT,

    /** 服务停止后进程或监听端口仍然存在。 */
    STOP_TIMEOUT,

    /** Redis或Sentinel端口已被其他进程占用。 */
    PORT_OCCUPIED,

    /** 磁盘、内存等本机资源不足。 */
    RESOURCE_INSUFFICIENT,

    /** PING、ROLE或INFO等健康检查失败。 */
    HEALTH_CHECK_FAILED,

    /** REPLICA复制状态未达到要求。 */
    REPLICATION_FAILED,

    /** Sentinel启动或监控状态未达到要求。 */
    SENTINEL_FAILED,

    /** 配置文件或版本包的SHA-256与平台提供的值不一致。 */
    CHECKSUM_MISMATCH,

    /** 平台要求的前置步骤尚未完成，例如Redis或Sentinel仍在运行。 */
    PRECONDITION_FAILED,

    /** 版本包格式、目录结构或Redis二进制不合法。 */
    PACKAGE_INVALID,

    /** Redis current版本链接切换失败。 */
    VERSION_SWITCH_FAILED,

    /** Redis版本回滚失败。 */
    VERSION_ROLLBACK_FAILED,

    /** 平台配置文件部署失败，但原配置仍可恢复或保持不变。 */
    CONFIG_DEPLOY_FAILED,

    /** 配置部署失败后无法完整恢复原配置，需要人工处理。 */
    CONFIG_ROLLBACK_FAILED,

    /** 无法归入其他分类的未知错误。 */
    UNKNOWN_ERROR
}
