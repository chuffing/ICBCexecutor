package com.icbc.nbfh.service.operation;

import com.icbc.nbfh.common.constant.OperationErrorCode;
import com.icbc.nbfh.entity.result.OperationResult;
import com.icbc.nbfh.entity.result.CommandResult;
import com.icbc.nbfh.service.command.FixedScriptExecutor;
import com.icbc.nbfh.service.health.SentinelHealthChecker;
import com.icbc.nbfh.entity.result.SentinelHealthResult;
import com.icbc.nbfh.entity.params.StartSentinelTaskParameters;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 本机 Sentinel 启动操作。
 *
 * <p>脚本只负责启动服务；Java 代码继续校验 Sentinel 实际监控的主库，
 * 防止服务器切换时加入网络中仍存在的旧同名 Sentinel 集群。</p>
 */
@Component
public class StartSentinelOperation {

    private static final String START_SCRIPT = "start-sentinel.sh";
    private static final long SCRIPT_TIMEOUT_SECONDS = 15;
    private static final long HEALTH_CHECK_INTERVAL_MILLIS = 1000;
    private static final long HEALTH_CHECK_TIMEOUT_SECONDS = 20;
    private static final int REQUIRED_STABLE_CHECKS = 3;

    @Autowired
    private FixedScriptExecutor fixedScriptExecutor;

    @Autowired
    private SentinelHealthChecker sentinelHealthChecker;

    /** 使用兼容模式启动 Sentinel，只做基础健康检查。 */
    public OperationResult execute() {
        return execute(Collections.emptyMap());
    }

    /**
     * 执行固定启动脚本，并按平台参数验收 Sentinel 最终监控的主库。
     *
     * @param params 可选的 expectedMasterHost 和 expectedMasterPort
     * @return 启动和拓扑验收结果
     */
    public OperationResult execute(Map<String, String> params) {
        long startTime = System.currentTimeMillis();

        final StartSentinelTaskParameters parameters;
        try {
            // 先校验参数，避免无效任务已经启动服务后才失败。
            parameters = StartSentinelTaskParameters.from(params);
        } catch (IllegalArgumentException e) {
            return OperationResult.failure(
                    OperationErrorCode.CONFIG_INVALID,
                    e.getMessage(),
                    startTime,
                    System.currentTimeMillis()
            );
        }

        try {
            CommandResult commandResult = fixedScriptExecutor.execute(
                    START_SCRIPT,
                    SCRIPT_TIMEOUT_SECONDS
            );

            if (commandResult.isTimedOut()) {
                return OperationResult.failure(
                        OperationErrorCode.COMMAND_TIMEOUT,
                        "Sentinel启动脚本执行超过15秒",
                        startTime,
                        System.currentTimeMillis()
                );
            }

            if (commandResult.getExitCode() != 0) {
                return OperationResult.failure(
                        OperationErrorCode.COMMAND_EXECUTION_FAILED,
                        "Sentinel启动脚本执行失败，退出码: " + commandResult.getExitCode(),
                        startTime,
                        System.currentTimeMillis()
                );
            }

            return waitForHealthy(startTime, parameters);
        } catch (FileNotFoundException e) {
            return OperationResult.failure(
                    OperationErrorCode.COMMAND_NOT_FOUND,
                    "Sentinel启动脚本不存在",
                    startTime,
                    System.currentTimeMillis()
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return OperationResult.failure(
                    OperationErrorCode.UNKNOWN_ERROR,
                    "Sentinel启动操作被中断",
                    startTime,
                    System.currentTimeMillis()
            );
        } catch (IOException e) {
            return OperationResult.failure(
                    OperationErrorCode.COMMAND_EXECUTION_FAILED,
                    "Sentinel启动脚本无法执行",
                    startTime,
                    System.currentTimeMillis()
            );
        } catch (Exception e) {
            return OperationResult.failure(
                    OperationErrorCode.UNKNOWN_ERROR,
                    "Sentinel启动发生未知错误",
                    startTime,
                    System.currentTimeMillis()
            );
        }
    }

    /** 等待 Sentinel 连续三次监控正确的主库，避免把瞬时状态当成成功。 */
    private OperationResult waitForHealthy(long startTime,
                                           StartSentinelTaskParameters parameters)
            throws InterruptedException {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(HEALTH_CHECK_TIMEOUT_SECONDS);
        SentinelHealthResult lastHealthResult = null;
        String lastValidationMessage = "未获得Sentinel健康检查结果";
        int consecutiveSuccessfulChecks = 0;

        while (true) {
            lastHealthResult = sentinelHealthChecker.check();
            lastValidationMessage = validateExpectedMaster(parameters, lastHealthResult);
            if (lastValidationMessage == null) {
                consecutiveSuccessfulChecks++;
                if (consecutiveSuccessfulChecks >= REQUIRED_STABLE_CHECKS) {
                    return OperationResult.success(
                            "Sentinel启动成功，连续稳定监控MASTER: "
                                    + lastHealthResult.getMasterName() + " "
                                    + lastHealthResult.getMasterHost() + ":"
                                    + lastHealthResult.getMasterPort(),
                            startTime,
                            System.currentTimeMillis()
                    );
                }
            } else {
                consecutiveSuccessfulChecks = 0;
            }

            if (System.nanoTime() >= deadline) {
                break;
            }
            Thread.sleep(HEALTH_CHECK_INTERVAL_MILLIS);
        }

        return OperationResult.failure(
                OperationErrorCode.SENTINEL_FAILED,
                "Sentinel在20秒内未达到期望拓扑: " + lastValidationMessage,
                startTime,
                System.currentTimeMillis()
        );
    }

    /** 比较健康检查结果与平台指定的主库地址和端口。 */
    private String validateExpectedMaster(StartSentinelTaskParameters parameters,
                                          SentinelHealthResult healthResult) {
        if (healthResult == null) {
            return "未获得Sentinel健康检查结果";
        }
        if (!healthResult.isHealthy()) {
            return healthResult.getMessage();
        }
        if (!parameters.hasExpectedMaster()) {
            return null;
        }
        if (!parameters.getExpectedMasterHost().equalsIgnoreCase(
                healthResult.getMasterHost())) {
            return "Sentinel主库地址不符合预期: expected="
                    + parameters.getExpectedMasterHost() + ", actual="
                    + healthResult.getMasterHost();
        }
        if (!parameters.getExpectedMasterPort().equals(healthResult.getMasterPort())) {
            return "Sentinel主库端口不符合预期: expected="
                    + parameters.getExpectedMasterPort() + ", actual="
                    + healthResult.getMasterPort();
        }
        return null;
    }
}
