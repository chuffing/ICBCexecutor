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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
     * 执行一个固定脚本。
     *
     * @param scriptName 脚本文件名，例如 start-redis.sh
     * @param timeoutSeconds 明确的执行超时秒数
     * @return 脚本进程执行结果
     */
    public CommandResult execute(String scriptName, long timeoutSeconds)
            throws IOException, InterruptedException {
        validateArguments(scriptName, timeoutSeconds);

        File scriptDirectory = getScriptDirectory();
        File scriptFile = new File(scriptDirectory, scriptName).getCanonicalFile();

        if (!scriptDirectory.equals(scriptFile.getParentFile())) {
            throw new IllegalArgumentException("脚本路径不在固定目录中");
        }
        if (!scriptFile.isFile()) {
            throw new FileNotFoundException("固定脚本不存在: " + scriptName);
        }

        ProcessBuilder processBuilder = new ProcessBuilder(
                "/bin/bash",
                scriptFile.getAbsolutePath()
        );
        processBuilder.directory(scriptDirectory);

        long startTime = System.currentTimeMillis();
        Process process = processBuilder.start();
        ExecutorService outputReaders = Executors.newFixedThreadPool(2);

        Future<String> stdoutFuture = outputReaders.submit(
                () -> readStream(process.getInputStream())
        );
        Future<String> stderrFuture = outputReaders.submit(
                () -> readStream(process.getErrorStream())
        );

        try {
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                stopProcess(process);
                return CommandResult.timedOut(
                        readFuture(stdoutFuture),
                        readFuture(stderrFuture),
                        startTime,
                        System.currentTimeMillis()
                );
            }

            return CommandResult.completed(
                    process.exitValue(),
                    readFuture(stdoutFuture),
                    readFuture(stderrFuture),
                    startTime,
                    System.currentTimeMillis()
            );
        } catch (InterruptedException e) {
            stopProcess(process);
            Thread.currentThread().interrupt();
            throw e;
        } finally {
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

    private String readFuture(Future<String> future) throws IOException, InterruptedException
    {
        try {
            return future.get();
        } catch (ExecutionException e) {
            throw new IOException("读取脚本输出失败", e.getCause());
        }
    }
    private void stopProcess(Process process) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(1, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(1, TimeUnit.SECONDS);
        }
    }
}
