package com.icbc.nbfh.service.operation;

import com.icbc.nbfh.common.constant.OperationErrorCode;
import com.icbc.nbfh.entity.result.OperationResult;
import com.icbc.nbfh.config.AgentProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 将Redis current软链接恢复到最近一次版本切换前记录的目录。
 *
 * <p>该操作供平台在跨服务器版本切换失败时调用。平台负责选择已经切换成功的节点，
 * Agent负责在本机确认服务停止、校验旧版本程序并安全恢复链接。</p>
 */
@Component
public class RollbackVersionOperation {

    private static final int PORT_CHECK_TIMEOUT_MILLIS = 500;
    private static final long COMMAND_TIMEOUT_SECONDS = 5;
    private static final Pattern VERSION_DIRECTORY_PATTERN =
            Pattern.compile("[0-9]+(?:\\.[0-9]+){1,3}");

    @Autowired
    private AgentProperties properties;

    /**
     * 校验回滚记录和本机停止状态，然后恢复current软链接。
     *
     * @return 统一的版本回滚结果
     */
    public OperationResult execute() {
        long startTime = System.currentTimeMillis();

        try {
            Path previousTarget = readPreviousTarget();
            validateRollbackTarget(previousTarget);

            // 回滚同样不能在Redis或Sentinel运行期间修改current。
            verifyLocalServicesStopped();

            Path currentTarget = readCurrentTarget();
            if (currentTarget.equals(previousTarget)) {
                return OperationResult.success(
                        "Redis current已经恢复到旧版本: " + previousTarget,
                        startTime,
                        System.currentTimeMillis()
                );
            }

            rollbackWithRecovery(currentTarget, previousTarget);
            return OperationResult.success(
                    "Redis版本链接已回滚: " + currentTarget + " -> " + previousTarget,
                    startTime,
                    System.currentTimeMillis()
            );
        } catch (ServiceNotStoppedException e) {
            return failure(OperationErrorCode.PRECONDITION_FAILED, e.getMessage(), startTime);
        } catch (RollbackRecoveryFailedException e) {
            return failure(OperationErrorCode.VERSION_ROLLBACK_FAILED, e.getMessage(), startTime);
        } catch (RollbackExecutionFailedException e) {
            return failure(OperationErrorCode.VERSION_ROLLBACK_FAILED, e.getMessage(), startTime);
        } catch (VersionCheckTimeoutException e) {
            return failure(OperationErrorCode.COMMAND_TIMEOUT, e.getMessage(), startTime);
        } catch (PackageValidationException e) {
            return failure(OperationErrorCode.VERSION_ROLLBACK_FAILED, e.getMessage(), startTime);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure(
                    OperationErrorCode.UNKNOWN_ERROR,
                    "Redis版本回滚检查被中断",
                    startTime
            );
        } catch (IOException | IllegalArgumentException | UnsupportedOperationException e) {
            return failure(
                    OperationErrorCode.VERSION_ROLLBACK_FAILED,
                    "Redis版本回滚准备或链接恢复失败",
                    startTime
            );
        }
    }

    /**
     * 从Agent状态文件读取最近一次切换前的版本目录。
     *
     * @return 规范化后的旧版本绝对路径
     * @throws IOException 状态文件不存在、为空或无法读取时抛出
     */
    private Path readPreviousTarget() throws IOException {
        Path previousVersionFile = getStateDirectory().resolve("previous-version-target");
        if (!Files.isRegularFile(previousVersionFile)) {
            throw new IOException("没有找到可用的旧版本记录");
        }
        String value = new String(
                Files.readAllBytes(previousVersionFile),
                StandardCharsets.UTF_8
        ).trim();
        if (value.isEmpty()) {
            throw new IOException("旧版本记录为空");
        }
        Path target = Paths.get(value);
        if (!target.isAbsolute()) {
            throw new IOException("旧版本记录不是绝对路径");
        }
        return target.normalize().toAbsolutePath();
    }

    /**
     * 限制回滚目标只能位于配置的Redis目录，并检查必要程序。
     *
     * <p>兼容根目录下的旧版布局，以及 releases 子目录下的新布局。</p>
     *
     * @param target 记录的旧版本目录
     * @throws IOException 读取程序版本失败时抛出
     * @throws InterruptedException 等待程序版本命令时线程被中断
     * @throws PackageValidationException 路径或程序不符合回滚要求时抛出
     * @throws VersionCheckTimeoutException 程序版本检查超时时抛出
     */
    private void validateRollbackTarget(Path target)
            throws IOException, InterruptedException,
            PackageValidationException, VersionCheckTimeoutException {
        Path parent = target.getParent();
        if (!(getRedisDirectory().equals(parent)
                || getReleasesDirectory().equals(parent))) {
            throw new PackageValidationException("旧版本目录不在允许的Redis路径中");
        }
        Path fileName = target.getFileName();
        if (fileName == null
                || !VERSION_DIRECTORY_PATTERN.matcher(fileName.toString()).matches()) {
            throw new PackageValidationException("旧版本目录名称不合法");
        }
        if (!Files.isDirectory(target)) {
            throw new PackageValidationException("旧版本目录不存在: " + target);
        }

        Path binDirectory = target.resolve("bin");
        Path redisServer = requireExecutable(binDirectory.resolve("redis-server"));
        requireExecutable(binDirectory.resolve("redis-cli"));
        requireExecutable(binDirectory.resolve("redis-sentinel"));
        validateRedisServer(redisServer);
    }

    /**
     * 要求旧版本目录中的程序存在并且具有执行权限。
     *
     * @param file 必须存在的程序
     * @return 原程序路径
     * @throws PackageValidationException 文件缺失或不可执行时抛出
     */
    private Path requireExecutable(Path file) throws PackageValidationException {
        if (!Files.isRegularFile(file) || !Files.isExecutable(file)) {
            throw new PackageValidationException(
                    "旧版本缺少可执行程序: " + file.getFileName()
            );
        }
        return file;
    }

    /**
     * 运行旧版本redis-server，确认程序能够正常报告版本。
     *
     * @param redisServer 旧版本redis-server路径
     * @throws IOException 程序无法执行或输出无法读取时抛出
     * @throws InterruptedException 等待程序时线程被中断
     * @throws PackageValidationException 程序退出异常或没有版本输出时抛出
     * @throws VersionCheckTimeoutException 程序超过规定时间仍未退出时抛出
     */
    private void validateRedisServer(Path redisServer)
            throws IOException, InterruptedException,
            PackageValidationException, VersionCheckTimeoutException {
        Process process = new ProcessBuilder(redisServer.toString(), "--version")
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(1, TimeUnit.SECONDS);
            throw new VersionCheckTimeoutException("旧版本redis-server检查超过5秒");
        }
        String output = readProcessOutput(process.getInputStream());
        if (process.exitValue() != 0 || !output.contains("v=")) {
            throw new PackageValidationException("旧版本redis-server无法通过版本检查");
        }
    }

    /**
     * 确认本机Sentinel和Redis都已停止且监听端口已经释放。
     *
     * @throws IOException 无法读取服务状态时抛出
     * @throws InterruptedException 等待systemctl时线程被中断
     * @throws ServiceNotStoppedException 任一服务仍运行或端口仍开放时抛出
     */
    private void verifyLocalServicesStopped()
            throws IOException, InterruptedException, ServiceNotStoppedException {
        AgentProperties.SentinelConfig sentinel = properties.getSentinel();
        validateEndpoint(
                sentinel == null ? null : sentinel.getHost(),
                sentinel == null ? 0 : sentinel.getPort(),
                "Sentinel"
        );
        verifyServiceStopped(
                requireServiceName(sentinel.getServiceName(), "Sentinel"),
                sentinel.getHost(),
                sentinel.getPort(),
                "Sentinel"
        );

        AgentProperties.RedisConfig redis = properties.getRedis();
        validateEndpoint(
                redis == null ? null : redis.getHost(),
                redis == null ? 0 : redis.getPort(),
                "Redis"
        );
        verifyServiceStopped(
                requireServiceName(redis.getServiceName(), "Redis"),
                redis.getHost(),
                redis.getPort(),
                "Redis"
        );
    }

    /**
     * 校验停止检查所需的服务地址和端口。
     *
     * @param host 服务地址
     * @param port 服务端口
     * @param label 服务名称
     */
    private void validateEndpoint(String host, int port, String label) {
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException(label + "地址不能为空");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException(label + "端口配置不正确");
        }
    }

    /**
     * 同时检查固定systemd服务状态和监听端口。
     *
     * @param serviceName systemd服务名
     * @param host 服务地址
     * @param port 服务端口
     * @param label 服务名称
     * @throws IOException 状态或端口检查失败时抛出
     * @throws InterruptedException 等待systemctl时线程被中断
     * @throws ServiceNotStoppedException 服务仍运行时抛出
     */
    private void verifyServiceStopped(String serviceName, String host, int port, String label)
            throws IOException, InterruptedException, ServiceNotStoppedException {
        String state = readSystemdState(serviceName);
        if (!("inactive".equals(state) || "failed".equals(state))) {
            throw new ServiceNotStoppedException(
                    label + "尚未停止，systemd状态: " + state
            );
        }
        if (isPortOpen(host, port)) {
            throw new ServiceNotStoppedException(
                    label + "端口仍在监听: " + host + ":" + port
            );
        }
    }

    /**
     * 使用固定systemctl命令读取服务状态。
     *
     * @param serviceName 代码中固定的systemd服务名
     * @return systemctl状态文本
     * @throws IOException 命令无法执行、超时或输出为空时抛出
     * @throws InterruptedException 等待命令时线程被中断
     */
    private String readSystemdState(String serviceName)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder("systemctl", "is-active", serviceName)
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(1, TimeUnit.SECONDS);
            throw new IOException("读取systemd服务状态超时: " + serviceName);
        }
        String output = readProcessOutput(process.getInputStream()).trim();
        if (output.isEmpty()) {
            throw new IOException("systemd服务状态为空: " + serviceName);
        }
        return output;
    }

    /**
     * 尝试连接服务端口，保守判断端口是否仍可能被占用。
     *
     * @param host 服务地址
     * @param port 服务端口
     * @return 连接成功或超时时返回true，明确拒绝连接时返回false
     * @throws IOException 发生其他网络错误时抛出
     */
    private boolean isPortOpen(String host, int port) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), PORT_CHECK_TIMEOUT_MILLIS);
            return true;
        } catch (ConnectException e) {
            return false;
        } catch (SocketTimeoutException e) {
            return true;
        }
    }

    /**
     * 读取current软链接指向的绝对版本目录。
     *
     * @return current实际指向目录
     * @throws IOException current不存在、不是软链接或目标不存在时抛出
     */
    private Path readCurrentTarget() throws IOException {
        Path currentLink = getCurrentLink();
        if (!Files.isSymbolicLink(currentLink)) {
            throw new IOException("Redis current不是软链接");
        }
        Path linkTarget = Files.readSymbolicLink(currentLink);
        Path resolved = linkTarget.isAbsolute()
                ? linkTarget
                : currentLink.getParent().resolve(linkTarget);
        resolved = resolved.normalize().toAbsolutePath();
        if (!Files.isDirectory(resolved)) {
            throw new IOException("Redis current指向的版本目录不存在");
        }
        return resolved;
    }

    /**
     * 执行回滚；若回滚失败，则自动恢复到回滚操作开始前的链接。
     *
     * @param currentTarget 回滚操作开始前的版本目录
     * @param previousTarget 需要恢复的旧版本目录
     * @throws RollbackExecutionFailedException 回滚失败但已恢复原链接时抛出
     * @throws RollbackRecoveryFailedException 回滚失败且原链接也无法恢复时抛出
     */
    private void rollbackWithRecovery(Path currentTarget, Path previousTarget)
            throws RollbackExecutionFailedException, RollbackRecoveryFailedException {
        try {
            replaceCurrentLink(previousTarget);
            verifyCurrentTarget(previousTarget);
        } catch (IOException | UnsupportedOperationException rollbackException) {
            try {
                replaceCurrentLink(currentTarget);
                verifyCurrentTarget(currentTarget);
            } catch (IOException | UnsupportedOperationException recoveryException) {
                recoveryException.addSuppressed(rollbackException);
                throw new RollbackRecoveryFailedException(
                        "Redis版本回滚失败，并且无法恢复回滚前的current链接",
                        recoveryException
                );
            }
            throw new RollbackExecutionFailedException(
                    "Redis版本回滚失败，current已恢复到回滚前版本",
                    rollbackException
            );
        }
    }

    /**
     * 创建唯一临时软链接并原子替换current。
     *
     * @param targetDirectory current需要指向的版本目录
     * @throws IOException 创建、移动或清理临时链接失败时抛出
     */
    private void replaceCurrentLink(Path targetDirectory) throws IOException {
        Path temporaryLink = getRedisDirectory().resolve(
                ".current-rollback-" + UUID.randomUUID()
        );
        try {
            Files.createSymbolicLink(temporaryLink, targetDirectory);
            moveReplacing(temporaryLink, getCurrentLink());
        } finally {
            Files.deleteIfExists(temporaryLink);
        }
    }

    /**
     * 读取并规范化 Redis 根目录。
     *
     * @return 配置的 Redis 根目录绝对路径
     */
    private Path getRedisDirectory() {
        AgentProperties.RedisConfig redis = properties.getRedis();
        return requireConfiguredPath(redis == null ? null : redis.getRootDir(), "Redis根目录");
    }

    /**
     * 读取并规范化 Redis 版本目录。
     *
     * @return 配置的 releases 目录绝对路径
     */
    private Path getReleasesDirectory() {
        AgentProperties.RedisConfig redis = properties.getRedis();
        return requireConfiguredPath(
                redis == null ? null : redis.getReleasesDir(),
                "Redis版本目录"
        );
    }

    /**
     * 读取并规范化 current 软链接路径。
     *
     * @return 配置的 current 绝对路径
     */
    private Path getCurrentLink() {
        AgentProperties.RedisConfig redis = properties.getRedis();
        return requireConfiguredPath(
                redis == null ? null : redis.getCurrentLink(),
                "Redis current路径"
        );
    }

    /**
     * 读取并规范化 Agent 状态目录。
     *
     * @return 配置的状态目录绝对路径
     */
    private Path getStateDirectory() {
        return requireConfiguredPath(properties.getStateDir(), "Agent状态目录");
    }

    /**
     * 校验路径配置并转换为规范化绝对路径。
     *
     * @param value 配置文件中的路径
     * @param label 错误信息使用的配置名称
     * @return 规范化后的绝对路径
     */
    private Path requireConfiguredPath(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + "配置不能为空");
        }
        return Paths.get(value).toAbsolutePath().normalize();
    }

    /**
     * 校验 systemd 服务名称已经配置。
     *
     * @param serviceName 配置的服务名称
     * @param label 服务显示名称
     * @return 去掉首尾空白的服务名称
     */
    private String requireServiceName(String serviceName, String label) {
        if (serviceName == null || serviceName.trim().isEmpty()) {
            throw new IllegalArgumentException(label + "服务名配置不能为空");
        }
        return serviceName.trim();
    }

    /**
     * 重新读取current并确认最终指向预期目录。
     *
     * @param expectedTarget 预期版本目录
     * @throws IOException current指向不正确时抛出
     */
    private void verifyCurrentTarget(Path expectedTarget) throws IOException {
        Path actualTarget = readCurrentTarget();
        if (!actualTarget.equals(expectedTarget.normalize().toAbsolutePath())) {
            throw new IOException("Redis current回滚后指向校验失败: " + actualTarget);
        }
    }

    /**
     * 优先使用原子移动替换目标，不支持时退化为普通替换移动。
     *
     * @param source 临时软链接
     * @param target 最终current路径
     * @throws IOException 移动失败时抛出
     */
    private void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 读取子进程全部输出。
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

    /**
     * 构造带统一起止时间的失败结果。
     *
     * @param errorCode 稳定错误分类
     * @param message 不包含密码或token的错误说明
     * @param startTime 操作开始时间
     * @return 失败结果
     */
    private OperationResult failure(OperationErrorCode errorCode, String message,
                                    long startTime) {
        return OperationResult.failure(
                errorCode,
                message,
                startTime,
                System.currentTimeMillis()
        );
    }

    /** 表示平台要求的停止步骤尚未完成。 */
    private static class ServiceNotStoppedException extends Exception {
        /** @param message 未停止的服务状态 */
        ServiceNotStoppedException(String message) {
            super(message);
        }
    }

    /** 表示旧版本目录或程序无法用于回滚。 */
    private static class PackageValidationException extends Exception {
        /** @param message 具体校验失败原因 */
        PackageValidationException(String message) {
            super(message);
        }
    }

    /** 表示旧版本程序检查超过允许时间。 */
    private static class VersionCheckTimeoutException extends Exception {
        /** @param message 超时说明 */
        VersionCheckTimeoutException(String message) {
            super(message);
        }
    }

    /** 表示回滚失败，但current已经恢复到回滚操作开始前的版本。 */
    private static class RollbackExecutionFailedException extends Exception {
        /**
         * @param message 回滚失败及恢复结果
         * @param cause 原始回滚异常
         */
        RollbackExecutionFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 表示回滚和恢复回滚前链接均失败，需要人工处理。 */
    private static class RollbackRecoveryFailedException extends Exception {
        /**
         * @param message 严重失败说明
         * @param cause 原始恢复异常
         */
        RollbackRecoveryFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
