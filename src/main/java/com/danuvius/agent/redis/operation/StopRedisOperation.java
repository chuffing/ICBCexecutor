package com.danuvius.agent.redis.operation;

import com.danuvius.agent.config.AgentProperties;
import com.danuvius.agent.common.operation.OperationErrorCode;
import com.danuvius.agent.common.operation.OperationResult;
import com.danuvius.agent.common.command.CommandResult;
import com.danuvius.agent.common.command.FixedScriptExecutor;
import com.danuvius.agent.redis.health.RedisHealthChecker;
import com.danuvius.agent.redis.health.RedisHealthResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;

/**
 * 本机Redis停止操作。
 */
@Component
public class StopRedisOperation {

    private static final String STOP_SCRIPT = "stop-redis.sh";
    private static final long SCRIPT_TIMEOUT_SECONDS = 10;
    private static final long STOP_CHECK_INTERVAL_MILLIS = 1000;
    private static final long STOP_CHECK_TIMEOUT_SECONDS = 10;
    private static final int PORT_CHECK_TIMEOUT_MILLIS = 500;

    @Autowired
    private FixedScriptExecutor fixedScriptExecutor;

    @Autowired
    private RedisHealthChecker redisHealthChecker;

    @Autowired
    private AgentProperties properties;

    /**
     * 执行固定停止脚本，并等待Redis无法PING且端口释放。
     */
    public OperationResult execute() {
        long startTime = System.currentTimeMillis();

        CommandResult commandResult;
        try {
            commandResult = fixedScriptExecutor.execute(
                    STOP_SCRIPT,
                    SCRIPT_TIMEOUT_SECONDS
            );
        } catch (FileNotFoundException e) {
            return OperationResult.failure(
                    OperationErrorCode.COMMAND_NOT_FOUND,
                    "Redis停止脚本不存在",
                    startTime,
                    System.currentTimeMillis()
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return OperationResult.failure(
                    OperationErrorCode.UNKNOWN_ERROR,
                    "Redis停止操作被中断",
                    startTime,
                    System.currentTimeMillis()
            );
        } catch (IOException e) {
            return OperationResult.failure(
                    OperationErrorCode.COMMAND_EXECUTION_FAILED,
                    "Redis停止脚本无法执行",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        if (commandResult.isTimedOut()) {
            return OperationResult.failure(
                    OperationErrorCode.COMMAND_TIMEOUT,
                    "Redis停止脚本执行超过10秒",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        if (commandResult.getExitCode() != 0) {
            return OperationResult.failure(
                    OperationErrorCode.COMMAND_EXECUTION_FAILED,
                    "Redis停止脚本执行失败，退出码: " + commandResult.getExitCode(),
                    startTime,
                    System.currentTimeMillis()
            );
        }

        try {
            return waitForStopped(startTime);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return OperationResult.failure(
                    OperationErrorCode.UNKNOWN_ERROR,
                    "等待Redis停止时被中断",
                    startTime,
                    System.currentTimeMillis()
            );
        } catch (IOException e) {
            return OperationResult.failure(
                    OperationErrorCode.HEALTH_CHECK_FAILED,
                    "Redis停止状态检查失败",
                    startTime,
                    System.currentTimeMillis()
            );
        } catch (Exception e) {
            return OperationResult.failure(
                    OperationErrorCode.UNKNOWN_ERROR,
                    "Redis停止发生未知错误",
                    startTime,
                    System.currentTimeMillis()
            );
        }
    }

    private OperationResult waitForStopped(long startTime)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(STOP_CHECK_TIMEOUT_SECONDS);
        RedisHealthResult lastHealthResult = null;
        boolean portOpen = true;

        while (true) {
            lastHealthResult = redisHealthChecker.check();
            portOpen = isRedisPortOpen();

            if (!lastHealthResult.isHealthy() && !portOpen) {
                return OperationResult.success(
                        "Redis已停止，6379端口已释放",
                        startTime,
                        System.currentTimeMillis()
                );
            }

            if (System.nanoTime() >= deadline) {
                break;
            }
            Thread.sleep(STOP_CHECK_INTERVAL_MILLIS);
        }

        if (!lastHealthResult.isHealthy() && portOpen) {
            return OperationResult.failure(
                    OperationErrorCode.PORT_OCCUPIED,
                    "Redis已无法PING，但端口仍被占用",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        return OperationResult.failure(
                OperationErrorCode.STOP_TIMEOUT,
                "Redis在10秒内未完全停止",
                startTime,
                System.currentTimeMillis()
        );
    }

    private boolean isRedisPortOpen() throws IOException {
        AgentProperties.RedisConfig redis = properties.getRedis();
        if (redis == null || redis.getHost() == null || redis.getHost().trim().isEmpty()) {
            throw new IOException("Redis连接配置不完整");
        }
        if (redis.getPort() <= 0 || redis.getPort() > 65535) {
            throw new IOException("Redis端口配置不正确");
        }

        try (Socket socket = new Socket()) {
            socket.connect(
                    new InetSocketAddress(redis.getHost(), redis.getPort()),
                    PORT_CHECK_TIMEOUT_MILLIS
            );
            return true;
        } catch (ConnectException e) {
            return false;
        } catch (SocketTimeoutException e) {
            return true;
        }
    }
}
