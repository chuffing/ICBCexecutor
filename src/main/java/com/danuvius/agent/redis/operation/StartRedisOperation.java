package com.danuvius.agent.redis.operation;

import com.danuvius.agent.common.command.CommandResult;
import com.danuvius.agent.common.command.FixedScriptExecutor;
import com.danuvius.agent.common.operation.OperationErrorCode;
import com.danuvius.agent.common.operation.OperationResult;
import com.danuvius.agent.redis.health.RedisHealthChecker;
import com.danuvius.agent.redis.health.RedisHealthResult;
import com.danuvius.agent.redis.model.StartRedisTaskParameters;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 本机 Redis 启动操作。
 *
 * <p>启动脚本只负责启动 systemd 服务。本类继续检查 Redis 角色和复制拓扑，
 * 并要求连续多次符合平台期望后才返回成功。</p>
 */
@Component
public class StartRedisOperation {

    private static final String START_SCRIPT = "start-redis.sh";
    private static final long SCRIPT_TIMEOUT_SECONDS = 10;
    private static final long HEALTH_CHECK_INTERVAL_MILLIS = 1000;
    private static final long HEALTH_CHECK_TIMEOUT_SECONDS = 20;
    private static final int REQUIRED_STABLE_CHECKS = 3;

    @Autowired
    private FixedScriptExecutor fixedScriptExecutor;

    @Autowired
    private RedisHealthChecker redisHealthChecker;

    /**
     * 使用兼容模式启动 Redis。
     *
     * @return 启动和健康检查结果
     */
    public OperationResult execute() {
        return execute(Collections.emptyMap());
    }

    /**
     * 执行固定启动脚本，并按平台期望等待 Redis 拓扑稳定。
     *
     * @param params START_REDIS 参数，可声明期望角色和主库地址
     * @return 启动和拓扑验收结果
     */
    public OperationResult execute(Map<String, String> params) {
        long startTime = System.currentTimeMillis();

        final StartRedisTaskParameters parameters;
        try {
            // 参数先校验，避免无效任务已经启动服务后才返回失败。
            parameters = StartRedisTaskParameters.from(params);
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
                        "Redis启动脚本执行超过10秒",
                        startTime,
                        System.currentTimeMillis()
                );
            }

            if (commandResult.getExitCode() != 0) {
                return OperationResult.failure(
                        OperationErrorCode.COMMAND_EXECUTION_FAILED,
                        "Redis启动脚本执行失败，退出码: " + commandResult.getExitCode(),
                        startTime,
                        System.currentTimeMillis()
                );
            }

            return waitForHealthy(startTime, parameters);
        } catch (FileNotFoundException e) {
            return OperationResult.failure(
                    OperationErrorCode.COMMAND_NOT_FOUND,
                    "Redis启动脚本不存在",
                    startTime,
                    System.currentTimeMillis()
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return OperationResult.failure(
                    OperationErrorCode.UNKNOWN_ERROR,
                    "Redis启动操作被中断",
                    startTime,
                    System.currentTimeMillis()
            );
        } catch (IOException e) {
            return OperationResult.failure(
                    OperationErrorCode.COMMAND_EXECUTION_FAILED,
                    "Redis启动脚本无法执行",
                    startTime,
                    System.currentTimeMillis()
            );
        } catch (Exception e) {
            return OperationResult.failure(
                    OperationErrorCode.UNKNOWN_ERROR,
                    "Redis启动发生未知错误",
                    startTime,
                    System.currentTimeMillis()
            );
        }
    }

    /**
     * 等待健康状态连续三次符合平台期望，过滤 Sentinel 调整角色时的瞬时状态。
     *
     * @param startTime 操作开始时间
     * @param parameters 已校验的拓扑参数
     * @return 成功结果或超时失败结果
     * @throws InterruptedException 等待期间线程被中断时抛出
     */
    private OperationResult waitForHealthy(long startTime,
                                           StartRedisTaskParameters parameters)
            throws InterruptedException {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(HEALTH_CHECK_TIMEOUT_SECONDS);
        RedisHealthResult lastHealthResult = null;
        String lastValidationMessage = "未获得Redis健康检查结果";
        int consecutiveSuccessfulChecks = 0;

        while (true) {
            lastHealthResult = redisHealthChecker.check();
            lastValidationMessage = validateExpectedTopology(parameters, lastHealthResult);

            if (lastValidationMessage == null) {
                consecutiveSuccessfulChecks++;
                if (consecutiveSuccessfulChecks >= REQUIRED_STABLE_CHECKS) {
                    return OperationResult.success(
                            buildSuccessMessage(lastHealthResult),
                            startTime,
                            System.currentTimeMillis()
                    );
                }
            } else {
                // 任何一次角色、主库地址或复制状态变化都会重新计算稳定次数。
                consecutiveSuccessfulChecks = 0;
            }

            if (System.nanoTime() >= deadline) {
                break;
            }
            Thread.sleep(HEALTH_CHECK_INTERVAL_MILLIS);
        }

        OperationErrorCode errorCode = isReplicaExpectation(parameters, lastHealthResult)
                ? OperationErrorCode.REPLICATION_FAILED
                : OperationErrorCode.START_TIMEOUT;
        return OperationResult.failure(
                errorCode,
                "Redis在20秒内未达到期望拓扑: " + lastValidationMessage,
                startTime,
                System.currentTimeMillis()
        );
    }

    /**
     * 判断单次健康结果是否符合平台期望。
     *
     * @param parameters 平台期望拓扑
     * @param healthResult 单次健康结果
     * @return 符合时返回 null，否则返回具体原因
     */
    private String validateExpectedTopology(StartRedisTaskParameters parameters,
                                            RedisHealthResult healthResult) {
        if (healthResult == null) {
            return "未获得Redis健康检查结果";
        }
        if (!healthResult.isHealthy()) {
            return healthResult.getMessage();
        }
        if (!parameters.hasExpectedTopology()) {
            return null;
        }
        if (!parameters.getExpectedRole().equals(healthResult.getRole())) {
            return "Redis角色不符合预期: expected=" + parameters.getExpectedRole()
                    + ", actual=" + healthResult.getRole();
        }
        if (StartRedisTaskParameters.ROLE_REPLICA.equals(parameters.getExpectedRole())) {
            if (!parameters.getExpectedMasterHost().equalsIgnoreCase(
                    healthResult.getMasterHost())) {
                return "Redis主库地址不符合预期: expected="
                        + parameters.getExpectedMasterHost()
                        + ", actual=" + healthResult.getMasterHost();
            }
            if (!parameters.getExpectedMasterPort().equals(healthResult.getMasterPort())) {
                return "Redis主库端口不符合预期: expected="
                        + parameters.getExpectedMasterPort()
                        + ", actual=" + healthResult.getMasterPort();
            }
        }
        return null;
    }

    /**
     * 判断失败是否属于副本复制拓扑未达到要求。
     *
     * @param parameters 平台期望拓扑
     * @param healthResult 最后一次健康结果
     * @return 应返回 REPLICATION_FAILED 时为 true
     */
    private boolean isReplicaExpectation(StartRedisTaskParameters parameters,
                                         RedisHealthResult healthResult) {
        return StartRedisTaskParameters.ROLE_REPLICA.equals(parameters.getExpectedRole())
                || (healthResult != null && "REPLICA".equals(healthResult.getRole()));
    }

    /**
     * 构造包含最终角色和主库地址的成功说明。
     *
     * @param healthResult 已连续稳定的健康结果
     * @return 可上报平台的成功消息
     */
    private String buildSuccessMessage(RedisHealthResult healthResult) {
        String message = "Redis启动成功，拓扑连续稳定"
                + REQUIRED_STABLE_CHECKS + "次，角色: " + healthResult.getRole();
        if ("REPLICA".equals(healthResult.getRole())) {
            message += "，MASTER: " + healthResult.getMasterHost()
                    + ":" + healthResult.getMasterPort();
        }
        return message;
    }
}
