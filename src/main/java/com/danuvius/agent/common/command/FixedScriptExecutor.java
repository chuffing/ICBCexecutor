package com.danuvius.agent.common.command;

import com.danuvius.agent.config.AgentProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/**
 * 固定Shell脚本执行器。
 *
 * 只允许执行配置目录中的无参数脚本，
 * 不接受平台下发的命令内容或任意脚本路径。
 */
@Component
public class FixedScriptExecutor {

    private static final String SCRIPT_NAME_PATTERN = "[a-z0-9][a-z0-9-]*\\.sh";

    @Autowired
    private AgentProperties properties;

    /**
     * 执行指定目录下的固定Shell脚本，控制超时时间并返回执行结果。
     *
     * @param scriptName 脚本文件名，例如 start-redis.sh
     * @param timeoutSeconds 明确的执行超时秒数
     * @return 脚本进程执行结果
     */
    public CommandResult execute(String scriptName, long timeoutSeconds)
            throws IOException, InterruptedException {

        // 校验脚本名称格式及超时时间是否合法。
        validateArguments(scriptName, timeoutSeconds);

        // 获取并检查配置中的固定脚本目录。
        File scriptDirectory = getScriptDirectory();
        File scriptFile = new File(scriptDirectory, scriptName).getCanonicalFile();

        if (!scriptDirectory.equals(scriptFile.getParentFile())) {
            throw new IllegalArgumentException("脚本路径不在固定目录中");
        }
        if (!scriptFile.isFile()) {
            throw new FileNotFoundException("固定脚本不存在: " + scriptName);
        }

        // processBuilder负责配置和启动进程。
        ProcessBuilder processBuilder = new ProcessBuilder(
                "/bin/bash",
                scriptFile.getAbsolutePath()
        );
        // 设置新进程的工作目录。
        processBuilder.directory(scriptDirectory);

        long startTime = System.currentTimeMillis();

        // 为脚本执行和输出读取设置统一截止时间。
        long deadlineNanos = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(timeoutSeconds);

        // 真正启动进程。
        Process process = processBuilder.start();

        // 创建一个包含两个工作线程的线程池，
        ExecutorService outputReaders = Executors.newFixedThreadPool(2);

        // 让两个线程分别读取标准输出和错误输出。
        Future<String> stdoutFuture = outputReaders.submit(
                () -> readStream(process.getInputStream())
        );
        Future<String> stderrFuture = outputReaders.submit(
                () -> readStream(process.getErrorStream())
        );

        try {
            // 计算剩余时间，等待Shell进程结束。
            long remainingNanos = Math.max(
                    0L, deadlineNanos - System.nanoTime()
            );

            boolean finished = process.waitFor(
                    remainingNanos, TimeUnit.NANOSECONDS
            );

            if (!finished) {
                stopProcess(process);
                return CommandResult.timedOut(
                        "", "",
                        startTime,
                        System.currentTimeMillis()
                );
            }

            // 在同一个截止时间内获取标准输出和错误输出。
            String stdout = readFuture(stdoutFuture, deadlineNanos);
            String stderr = readFuture(stderrFuture, deadlineNanos);

            return CommandResult.completed(
                    process.exitValue(),
                    stdout,
                    stderr,
                    startTime,
                    System.currentTimeMillis()
            );
        } catch (TimeoutException e) {
            // Shell进程可能已结束，但读取输出仍然超时。
            if (process.isAlive()) {
                stopProcess(process);
            }
            return CommandResult.timedOut(
                    "", "",
                    startTime,
                    System.currentTimeMillis()
            );
        } catch (InterruptedException e) {
            stopProcess(process);
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            // 取消未结束的读取任务，关闭线程池。
            stdoutFuture.cancel(true);
            stderrFuture.cancel(true);
            outputReaders.shutdownNow();
        }
    }

    /**
     * 读取并校验配置中的固定脚本目录。
     *
     * @return 规范化后的脚本目录
     * @throws IOException 无法规范化目录路径时抛出
     */
    private File getScriptDirectory() throws IOException {
        String configuredDirectory = properties.getScriptDir();
        if (configuredDirectory == null || configuredDirectory.trim().isEmpty()) {
            throw new IllegalArgumentException("固定脚本目录配置不能为空");
        }
        File directory = new File(configuredDirectory.trim()).getCanonicalFile();
        if (!directory.isDirectory()) {
            throw new FileNotFoundException("固定脚本目录不存在: " + directory);
        }
        return directory;
    }

    private void validateArguments(String scriptName, long timeoutSeconds) {
        if (scriptName == null || !scriptName.matches(SCRIPT_NAME_PATTERN)) {
            throw new IllegalArgumentException("脚本名称不合法");
        }
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("脚本超时时间必须大于0秒");
        }
    }

    /** 按UTF-8编码读取进程输出流，并将内容拼接成字符串。 */
    private String readStream(InputStream inputStream) throws IOException {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() > 0) {
                    output.append(System.lineSeparator());
                }
                output.append(line);
            }
        }
        return output.toString();
    }

    /** 在指定截止时间内获取异步读取结果，避免无限期阻塞。 */
    private String readFuture(Future<String> future, long deadlineNanos) throws IOException, InterruptedException, TimeoutException {

        long remainingNanos = Math.max(
                0L,deadlineNanos - System.nanoTime()
        );

        try {
            return future.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (ExecutionException e) {
            throw new IOException("读取脚本输出失败", e.getCause());
        }
    }

    /** 尝试终止进程，未及时退出时强制结束进程。 */
    private void stopProcess(Process process) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(1, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(1, TimeUnit.SECONDS);
        }
    }
}
