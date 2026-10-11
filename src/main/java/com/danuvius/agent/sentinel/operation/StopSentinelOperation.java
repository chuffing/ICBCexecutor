package com.danuvius.agent.sentinel.operation;

import com.danuvius.agent.config.AgentProperties;
import com.danuvius.agent.common.operation.OperationErrorCode;
import com.danuvius.agent.common.operation.OperationResult;
import com.danuvius.agent.common.command.CommandResult;
import com.danuvius.agent.common.command.FixedScriptExecutor;
import com.danuvius.agent.sentinel.health.SentinelHealthChecker;
import com.danuvius.agent.sentinel.health.SentinelHealthResult;
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
 * 本机Sentinel停止操作。
 */
@Component
public class StopSentinelOperation {

    private static final String STOP_SCRIPT = "stop-sentinel.sh";
    private static final long SCRIPT_TIMEOUT_SECONDS = 15;
    private static final long STOP_CHECK_INTERVAL_MILLIS = 1000;
    private static final long STOP_CHECK_TIMEOUT_SECONDS = 10;
    private static final int PORT_CHECK_TIMEOUT_MILLIS = 500;

    @Autowired
    private FixedScriptExecutor fixedScriptExecutor;

    @Autowired
    private SentinelHealthChecker sentinelHealthChecker;

    @Autowired
    private AgentProperties properties;

    /**
     * 执行本机Sentinel停止脚本，并检查进程服务是否停止及监听端口是否释放。
     */
    public OperationResult execute() {
        long startTime = System.currentTimeMillis();

        // Shell命令执行结果。
        CommandResult commandResult;

        try {
            commandResult = fixedScriptExecutor.execute(
                    STOP_SCRIPT,
                    SCRIPT_TIMEOUT_SECONDS
            );
        } catch (FileNotFoundException e) {
            return OperationResult.failure(
                    OperationErrorCode.COMMAND_NOT_FOUND,
                    "Sentinel停止脚本不存在",
                    startTime,
                    System.currentTimeMillis()
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return OperationResult.failure(
                    OperationErrorCode.UNKNOWN_ERROR,
                    "Sentinel停止操作被中断",
                    startTime,
                    System.currentTimeMillis()
            );
        } catch (IOException e) {
            return OperationResult.failure(
                    OperationErrorCode.COMMAND_EXECUTION_FAILED,
                    "Sentinel停止脚本无法执行",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        // 判断脚本是否执行超时。
        if (commandResult.isTimedOut()) {
            return OperationResult.failure(
                    OperationErrorCode.COMMAND_TIMEOUT,
                    "Sentinel停止脚本执行超过15秒",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        if (commandResult.getExitCode() != 0) {
            return OperationResult.failure(
                    OperationErrorCode.COMMAND_EXECUTION_FAILED,
                    "Sentinel停止脚本执行失败，退出码: " + commandResult.getExitCode(),
                    startTime,
                    System.currentTimeMillis()
            );
        }

        // 验证Sentinel是否真的停止。
        try {
            return waitForStopped(startTime);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return OperationResult.failure(
                    OperationErrorCode.UNKNOWN_ERROR,
                    "等待Sentinel停止时被中断",
                    startTime,
                    System.currentTimeMillis()
            );
        } catch (IOException e) {
            return OperationResult.failure(
                    OperationErrorCode.HEALTH_CHECK_FAILED,
                    "Sentinel停止状态检查失败",
                    startTime,
                    System.currentTimeMillis()
            );
        } catch (Exception e) {
            return OperationResult.failure(
                    OperationErrorCode.UNKNOWN_ERROR,
                    "Sentinel停止发生未知错误",
                    startTime,
                    System.currentTimeMillis()
            );
        }
    }

    /** 定时检查Sentinel健康状态和监听端口，确认服务完全停止。 */
    private OperationResult waitForStopped(long startTime)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(STOP_CHECK_TIMEOUT_SECONDS);
        SentinelHealthResult lastHealthResult = null;
        boolean portOpen = true;

        while (true) {
            // 检查Sentinel的健康状态，包括能否正常响应。
            lastHealthResult = sentinelHealthChecker.check();
            // 检查Sentinel的监听端口是否仍然开放。
            portOpen = isSentinelPortOpen();

            if (!lastHealthResult.isHealthy() && !portOpen) {
                return OperationResult.success(
                        "Sentinel已停止，26379端口已释放",
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
                    "Sentinel已无法PING，但端口仍被占用",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        return OperationResult.failure(
                OperationErrorCode.STOP_TIMEOUT,
                "Sentinel在10秒内未完全停止",
                startTime,
                System.currentTimeMillis()
        );
    }

    private boolean isSentinelPortOpen() throws IOException {
        AgentProperties.SentinelConfig sentinel = properties.getSentinel();
        if (sentinel == null || sentinel.getHost() == null
                || sentinel.getHost().trim().isEmpty()) {
            throw new IOException("Sentinel连接配置不完整");
        }
        if (sentinel.getPort() <= 0 || sentinel.getPort() > 65535) {
            throw new IOException("Sentinel端口配置不正确");
        }

        try (Socket socket = new Socket()) {
            socket.connect(
                    new InetSocketAddress(sentinel.getHost(), sentinel.getPort()),
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
