package com.danuvius.agent.host.operation;

import com.danuvius.agent.common.operation.OperationErrorCode;
import com.danuvius.agent.common.operation.OperationResult;
import com.danuvius.agent.config.AgentProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.BindException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 检查一台新服务器是否满足平台部署Redis的基础条件。
 *
 * <p>本操作只读取状态，不创建目录、不修改权限，也不启动或停止服务。
 * 服务器基础环境仍由人工或公司的基础设施自动化提前准备。</p>
 */
@Component
public class PrecheckHostOperation {

    private static final long SYSTEMCTL_TIMEOUT_SECONDS = 5;

    @Autowired
    private AgentProperties properties;

    /**
     * 汇总检查新服务器的系统、目录、脚本、服务和端口状态。
     *
     * @return 所有检查均通过时返回成功，否则一次性返回发现的问题列表
     */
    public OperationResult execute() {
        long startTime = System.currentTimeMillis();
        List<String> problems = new ArrayList<>();

        AgentProperties.RedisConfig redis = properties.getRedis();
        AgentProperties.SentinelConfig sentinel = properties.getSentinel();
        Path agentDirectory = configuredPath(properties.getAgentRoot(), "Agent安装目录", problems);
        Path scriptDirectory = configuredPath(properties.getScriptDir(), "Agent脚本目录", problems);
        Path releasesDirectory = configuredPath(
                redis == null ? null : redis.getReleasesDir(),
                "Redis版本目录",
                problems
        );
        Path configDirectory = configuredPath(
                redis == null ? null : redis.getConfigDir(),
                "Redis配置目录",
                problems
        );
        Path dataDirectory = configuredPath(
                redis == null ? null : redis.getDataDir(),
                "Redis数据目录",
                problems
        );
        Path currentLink = configuredPath(
                redis == null ? null : redis.getCurrentLink(),
                "Redis current路径",
                problems
        );

        checkOperatingSystem(problems);
        checkWritableDirectory(agentDirectory, "Agent安装目录", problems);
        checkWritableDirectory(releasesDirectory, "Redis版本目录", problems);
        checkWritableDirectory(configDirectory, "Redis配置目录", problems);
        checkWritableDirectory(dataDirectory, "Redis数据目录", problems);
        checkRequiredScripts(scriptDirectory, problems);
        checkReusableCurrentLink(currentLink, releasesDirectory, problems);

        boolean redisActive;
        boolean sentinelActive;
        try {
            redisActive = checkSystemdService(
                    redis == null ? null : redis.getServiceName(),
                    "Redis",
                    problems
            );
            sentinelActive = checkSystemdService(
                    sentinel == null ? null : sentinel.getServiceName(),
                    "Sentinel",
                    problems
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return OperationResult.failure(
                    OperationErrorCode.UNKNOWN_ERROR,
                    "新服务器环境预检查被中断",
                    startTime,
                    System.currentTimeMillis()
            );
        }

        // 已由对应systemd服务占用的端口是可接受的，后续停止步骤会释放它。
        checkConfiguredPort(redis == null ? 0 : redis.getPort(),
                "Redis", redisActive, problems);
        checkConfiguredPort(
                sentinel == null ? 0 : sentinel.getPort(),
                "Sentinel", sentinelActive,
                problems
        );

        if (!problems.isEmpty()) {
            return OperationResult.failure(
                    OperationErrorCode.PRECONDITION_FAILED,
                    "新服务器环境预检查未通过: " + String.join("; ", problems),
                    startTime,
                    System.currentTimeMillis()
            );
        }

        return OperationResult.success(
                "新服务器环境预检查通过，可以部署Redis版本和配置",
                startTime,
                System.currentTimeMillis()
        );
    }

    /**
     * 确认Agent运行在Linux系统上。
     *
     * @param problems 用于汇总全部检查问题的列表
     */
    private void checkOperatingSystem(List<String> problems) {
        String operatingSystem = System.getProperty("os.name", "");
        if (!operatingSystem.toLowerCase(Locale.ROOT).contains("linux")) {
            problems.add("当前操作系统不是Linux: " + operatingSystem);
        }
    }

    /**
     * 确认人工预先创建的目录存在，并且Agent进程具有写入权限。
     *
     * @param directory 需要检查的固定目录
     * @param label 面向运维人员的目录名称
     * @param problems 用于汇总全部检查问题的列表
     */
    private void checkWritableDirectory(Path directory, String label,
                                        List<String> problems) {
        if (directory == null) {
            return;
        }
        if (!Files.isDirectory(directory)) {
            problems.add(label + "不存在: " + directory);
            return;
        }
        if (!Files.isReadable(directory) || !Files.isWritable(directory)) {
            problems.add(label + "缺少读写权限: " + directory);
        }
    }

    /**
     * 检查后续启动和停止流程依赖的固定脚本。
     *
     * @param problems 用于汇总全部检查问题的列表
     */
    private void checkRequiredScripts(Path scriptDirectory, List<String> problems) {
        if (scriptDirectory == null) {
            return;
        }
        checkExecutableScript(scriptDirectory, "start-redis.sh", problems);
        checkExecutableScript(scriptDirectory, "stop-redis.sh", problems);
        checkExecutableScript(scriptDirectory, "start-sentinel.sh", problems);
        checkExecutableScript(scriptDirectory, "stop-sentinel.sh", problems);
    }

    /**
     * 确认一个固定脚本存在并具有执行权限。
     *
     * @param scriptName 固定脚本文件名
     * @param problems 用于汇总全部检查问题的列表
     */
    private void checkExecutableScript(Path scriptDirectory, String scriptName,
                                       List<String> problems) {
        Path script = scriptDirectory.resolve(scriptName);
        if (!Files.isRegularFile(script) || !Files.isExecutable(script)) {
            problems.add("固定脚本不存在或不可执行: " + script);
        }
    }

    /**
     * 检查current路径是否可以被后续版本切换步骤安全复用。
     *
     * <p>首次部署时该路径可以不存在；曾经运行过Redis的旧服务器允许保留软链接，
     * 但链接必须指向配置的releases目录，避免覆盖人工维护的普通文件或未知目录。</p>
     *
     * @param currentLink Redis当前版本软链接
     * @param releasesDirectory Redis版本目录
     * @param problems 用于汇总全部检查问题的列表
     */
    private void checkReusableCurrentLink(Path currentLink, Path releasesDirectory,
                                          List<String> problems) {
        if (currentLink == null
                || !Files.exists(currentLink, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isSymbolicLink(currentLink)) {
            problems.add("Redis current路径不是软链接: " + currentLink);
            return;
        }
        if (releasesDirectory == null) {
            return;
        }
        try {
            Path linkTarget = Files.readSymbolicLink(currentLink);
            Path resolvedTarget = currentLink.getParent()
                    .resolve(linkTarget)
                    .toAbsolutePath()
                    .normalize();
            if (!resolvedTarget.startsWith(releasesDirectory)) {
                problems.add("Redis current软链接指向版本目录之外: " + resolvedTarget);
            }
        } catch (IOException e) {
            problems.add("Redis current软链接无法读取: " + currentLink);
        }
    }

    /**
     * 确认systemd单元已经由人工安装，并且当前处于停止状态。
     *
     * @param serviceName 固定systemd服务名
     * @param serviceLabel 面向运维人员的服务名称
     * @param problems 用于汇总全部检查问题的列表
     * @throws InterruptedException 等待systemctl命令时线程被中断
     */
    private boolean checkSystemdService(String serviceName, String serviceLabel,
                                        List<String> problems) throws InterruptedException {
        if (serviceName == null || serviceName.trim().isEmpty()) {
            problems.add(serviceLabel + "服务名配置不能为空");
            return false;
        }
        try {
            String loadState = runSystemctl(
                    "show", serviceName, "--property=LoadState", "--value"
            );
            if (!"loaded".equals(loadState)) {
                problems.add(serviceLabel + " systemd单元未正确加载: " + loadState);
                return false;
            }

            String activeState = runSystemctl("is-active", serviceName);
            if ("active".equals(activeState)) {
                return true;
            }
            if ("inactive".equals(activeState) || "failed".equals(activeState)) {
                return false;
            }
            problems.add(serviceLabel + "服务处于不稳定状态: " + activeState);
            return false;
        } catch (IOException e) {
            problems.add(serviceLabel + " systemd状态检查失败");
            return false;
        }
    }

    /**
     * 把目录配置转换为规范化绝对路径，并把配置错误加入统一问题列表。
     *
     * @param value 配置文件中的路径
     * @param label 面向运维人员的配置名称
     * @param problems 用于汇总全部检查问题的列表
     * @return 合法时返回绝对路径，非法时返回null
     */
    private Path configuredPath(String value, String label, List<String> problems) {
        if (value == null || value.trim().isEmpty()) {
            problems.add(label + "配置不能为空");
            return null;
        }
        try {
            return Paths.get(value).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            problems.add(label + "配置不合法: " + value);
            return null;
        }
    }

    /**
     * 执行参数完全由代码固定的systemctl命令并读取输出。
     *
     * @param arguments systemctl参数，不包含平台传入内容
     * @return 去掉首尾空白的命令输出
     * @throws IOException 命令无法执行、超时或没有输出时抛出
     * @throws InterruptedException 等待命令时线程被中断
     */
    private String runSystemctl(String... arguments)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("systemctl");
        for (String argument : arguments) {
            command.add(argument);
        }

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(SYSTEMCTL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(1, TimeUnit.SECONDS);
            throw new IOException("systemctl执行超时");
        }
        String output = readProcessOutput(process.getInputStream()).trim();
        if (output.isEmpty()) {
            throw new IOException("systemctl没有返回状态");
        }
        return output;
    }

    /**
     * 检查配置端口合法并且当前可以被新服务绑定。
     *
     * @param port 待检查端口
     * @param serviceLabel 面向运维人员的服务名称
     * @param problems 用于汇总全部检查问题的列表
     */
    private void checkConfiguredPort(int port, String serviceLabel,
                                     boolean serviceActive,
                                     List<String> problems) {
        if (port <= 0 || port > 65535) {
            problems.add(serviceLabel + "端口配置不正确: " + port);
            return;
        }
        // 服务运行时占用自己的端口是正常状态；停止操作还会再次确认端口已释放。
        if (!serviceActive && !canBindPort(port)) {
            problems.add(serviceLabel + "服务已停止但端口仍被其他进程占用: " + port);
        }
    }

    /**
     * 临时绑定本机端口来判断是否已经被其他进程占用，检查完成后立即释放。
     *
     * @param port 待检查端口
     * @return 能够绑定时返回true，已占用或无法确认时返回false
     */
    private boolean canBindPort(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(port));
            return true;
        } catch (BindException e) {
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 读取子进程的全部标准输出。
     *
     * @param input 子进程标准输出流
     * @return UTF-8文本
     * @throws IOException 读取失败时抛出
     */
    private String readProcessOutput(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int bytesRead;
        while ((bytesRead = input.read(buffer)) != -1) {
            output.write(buffer, 0, bytesRead);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}
