package com.danuvius.agent.version.operation;

import com.danuvius.agent.common.operation.OperationErrorCode;
import com.danuvius.agent.common.operation.OperationResult;
import com.danuvius.agent.config.AgentProperties;
import com.danuvius.agent.version.model.VersionTaskParameters;
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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 将 Redis current 软链接切换到已经部署并校验通过的版本目录。
 *
 * <p>停止服务的全局顺序由平台负责。本操作只确认本机 Redis 和 Sentinel
 * 已经完全停止，不会主动停止或启动任何服务。</p>
 */
@Component
public class SwitchVersionOperation {

    private static final int PORT_CHECK_TIMEOUT_MILLIS = 500;
    private static final long COMMAND_TIMEOUT_SECONDS = 5;

    @Autowired
    private AgentProperties properties;

    /**
     * 校验目标版本和本机停止状态，然后原子替换 current 软链接。
     *
     * @param taskNo 平台业务任务编号
     * @param params 任务参数，必须包含 version 和 packageSha256
     * @return 统一的版本切换结果
     */
    public OperationResult execute(String taskNo, Map<String, String> params) {
        long startTime = System.currentTimeMillis();

        try {
            VersionTaskParameters parameters = VersionTaskParameters.from(taskNo, params);
            Path targetDirectory = resolveTargetDirectory(parameters.getVersion());

            OperationResult targetCheck = validateTargetDirectory(
                    targetDirectory,
                    parameters,
                    startTime
            );
            if (targetCheck != null) { // 检查成功返回null
                return targetCheck;
            }

            // 平台应先停止三台 Sentinel，再停止三台 Redis；这里执行最后一道本机确认。
            verifyLocalServicesStopped();

            Path previousTarget = readCurrentTargetIfPresent();
            if (previousTarget != null && previousTarget.equals(targetDirectory)) {
                return OperationResult.success(
                        "Redis current已经指向目标版本: " + parameters.getVersion(),
                        startTime,
                        System.currentTimeMillis()
                );
            }

            if (previousTarget == null) {
                // 新服务器首次激活没有旧版本，清除可能残留的回滚记录，避免误回滚。
                clearPreviousTarget();
            } else {
                // 版本升级先持久化旧链接目标，后续ROLLBACK_VERSION才能准确恢复。
                writePreviousTarget(previousTarget);
            }
            switchWithAutomaticRollback(previousTarget, targetDirectory);

            if (previousTarget == null) {
                return OperationResult.success(
                        "Redis版本已首次激活: " + targetDirectory,
                        startTime,
                        System.currentTimeMillis()
                );
            }
            return OperationResult.success(
                    "Redis版本链接已切换: " + previousTarget + " -> " + targetDirectory,
                    startTime,
                    System.currentTimeMillis()
            );
        } catch (IllegalArgumentException e) {
            return failure(OperationErrorCode.CONFIG_INVALID, e.getMessage(), startTime);
        } catch (ServiceNotStoppedException e) {
            return failure(OperationErrorCode.PRECONDITION_FAILED, e.getMessage(), startTime);
        } catch (AutomaticRollbackFailedException e) {
            return failure(
                    OperationErrorCode.VERSION_ROLLBACK_FAILED,
                    e.getMessage(),
                    startTime
            );
        } catch (VersionSwitchFailedException e) {
            return failure(
                    OperationErrorCode.VERSION_SWITCH_FAILED,
                    e.getMessage(),
                    startTime
            );
        } catch (VersionCheckTimeoutException e) {
            return failure(OperationErrorCode.COMMAND_TIMEOUT, e.getMessage(), startTime);
        } catch (PackageValidationException e) {
            return failure(OperationErrorCode.PACKAGE_INVALID, e.getMessage(), startTime);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure(
                    OperationErrorCode.UNKNOWN_ERROR,
                    "Redis版本切换检查被中断",
                    startTime
            );
        } catch (IOException | UnsupportedOperationException e) {
            return failure(
                    OperationErrorCode.VERSION_SWITCH_FAILED,
                    "Redis current版本链接切换失败",
                    startTime
            );
        }
    }

    /**
     * 根据经过格式校验的版本号构造正式版本目录。
     *
     * @param version Redis版本号
     * @return releases目录下的目标版本路径
     */
    private Path resolveTargetDirectory(String version) {
        Path releasesDirectory = getReleasesDirectory();
        Path target = releasesDirectory.resolve(version).normalize().toAbsolutePath();
        if (!target.getParent().equals(releasesDirectory)) {
            throw new IllegalArgumentException("Redis版本目录不合法");
        }
        return target;
    }

    /**
     * 校验目标目录、版本包摘要、必要程序以及 redis-server 的实际版本。
     *
     * @param targetDirectory 已部署版本目录
     * @param parameters 平台下发的版本参数
     * @param startTime 操作开始时间
     * @return 校验成功时返回 null，否则返回对应失败结果
     * @throws IOException 读取文件或执行程序失败时抛出
     * @throws InterruptedException 等待版本命令时线程被中断
     * @throws PackageValidationException 版本目录内容不合法时抛出
     * @throws VersionCheckTimeoutException 版本检查命令超时时抛出
     */
    private OperationResult validateTargetDirectory(
            Path targetDirectory,
            VersionTaskParameters parameters,
            long startTime) throws IOException, InterruptedException,
            PackageValidationException, VersionCheckTimeoutException {
        if (!Files.isDirectory(targetDirectory)) {
            return failure(
                    OperationErrorCode.PACKAGE_INVALID,
                    "目标Redis版本尚未部署: " + parameters.getVersion(),
                    startTime
            );
        }

        Path marker = targetDirectory.resolve(".package.sha256");
        if (!Files.isRegularFile(marker)) {
            return failure(
                    OperationErrorCode.PACKAGE_INVALID,
                    "目标Redis版本缺少SHA-256标记",
                    startTime
            );
        }
        String recordedSha256 = new String(
                Files.readAllBytes(marker),
                StandardCharsets.UTF_8
        ).trim();
        if (!parameters.getPackageSha256().equalsIgnoreCase(recordedSha256)) {
            return failure(
                    OperationErrorCode.CHECKSUM_MISMATCH,
                    "目标Redis版本与平台审核的版本包不一致",
                    startTime
            );
        }

        Path binDirectory = targetDirectory.resolve("bin");
        Path redisServer = requireExecutable(binDirectory.resolve("redis-server"));
        requireExecutable(binDirectory.resolve("redis-cli"));
        requireExecutable(binDirectory.resolve("redis-sentinel"));
        validateActualVersion(redisServer, parameters.getVersion());
        return null;
    }

    /**
     * 确认Redis和Sentinel均处于停止状态且监听端口已经释放。
     *
     * @throws IOException 无法读取本机服务状态时抛出
     * @throws InterruptedException 等待systemctl命令时线程被中断
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
     * 校验服务连接地址，避免停止检查使用空地址或非法端口。
     *
     * @param host 服务地址
     * @param port 服务端口
     * @param serviceLabel 日志和错误信息中的服务名称
     */
    private void validateEndpoint(String host, int port, String serviceLabel) {
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException(serviceLabel + "地址不能为空");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException(serviceLabel + "端口配置不正确");
        }
    }

    /**
     * 同时检查systemd状态和监听端口，避免仅凭PING失败误判服务已停止。
     *
     * @param serviceName 固定的systemd服务名
     * @param host 服务地址
     * @param port 服务端口
     * @param serviceLabel 错误信息中的服务名称
     * @throws IOException 无法执行systemctl或连接检查时抛出
     * @throws InterruptedException 等待systemctl时线程被中断
     * @throws ServiceNotStoppedException 服务仍运行或端口仍开放时抛出
     */
    private void verifyServiceStopped(String serviceName, String host, int port,
                                      String serviceLabel)
            throws IOException, InterruptedException, ServiceNotStoppedException {
        String systemdState = readSystemdState(serviceName);
        if (!("inactive".equals(systemdState) || "failed".equals(systemdState))) {
            throw new ServiceNotStoppedException(
                    serviceLabel + "尚未停止，systemd状态: " + systemdState
            );
        }
        if (isPortOpen(host, port)) {
            throw new ServiceNotStoppedException(
                    serviceLabel + "端口仍在监听: " + host + ":" + port
            );
        }
    }

    /**
     * 使用固定systemctl命令读取服务状态，不接收平台传入的命令内容。
     *
     * @param serviceName 代码中固定的systemd服务名
     * @return systemctl输出的状态文本
     * @throws IOException 命令无法执行、超时或没有输出时抛出
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
     * 尝试连接配置端口，连接成功或连接超时都按“尚未确认释放”处理。
     *
     * @param host 服务地址
     * @param port 服务端口
     * @return 端口可能仍被占用时返回true，明确拒绝连接时返回false
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
     * 要求版本目录中的程序存在并且具有执行权限。
     *
     * @param file 程序路径
     * @return 原程序路径
     * @throws PackageValidationException 文件缺失或不可执行时抛出
     */
    private Path requireExecutable(Path file) throws PackageValidationException {
        if (!Files.isRegularFile(file) || !Files.isExecutable(file)) {
            throw new PackageValidationException(
                    "目标Redis版本缺少可执行程序: " + file.getFileName()
            );
        }
        return file;
    }

    /**
     * 运行目标redis-server确认二进制实际版本与平台参数一致。
     *
     * @param redisServer 目标版本的redis-server程序
     * @param version 平台声明的版本号
     * @throws IOException 程序无法启动或输出无法读取时抛出
     * @throws InterruptedException 等待程序时线程被中断
     * @throws PackageValidationException 程序返回失败或版本不一致时抛出
     * @throws VersionCheckTimeoutException 程序超过规定时间仍未退出时抛出
     */
    private void validateActualVersion(Path redisServer, String version)
            throws IOException, InterruptedException,
            PackageValidationException, VersionCheckTimeoutException {
        Process process = new ProcessBuilder(redisServer.toString(), "--version")
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(1, TimeUnit.SECONDS);
            throw new VersionCheckTimeoutException("redis-server版本检查超过5秒");
        }
        String output = readProcessOutput(process.getInputStream());
        if (process.exitValue() != 0 || !output.contains("v=" + version)) {
            throw new PackageValidationException("redis-server版本与切换任务不一致");
        }
    }

    /**
     * 读取并规范化current软链接当前指向的绝对路径。
     *
     * @return 切换前的版本目录绝对路径；首次激活且current不存在时返回null
     * @throws IOException current已经存在但不是软链接，或目标目录不存在时抛出
     */
    private Path readCurrentTargetIfPresent() throws IOException {
        Path currentLink = getCurrentLink();
        if (!Files.exists(currentLink, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (!Files.isSymbolicLink(currentLink)) {
            throw new IOException("Redis current不是软链接");
        }
        Path linkTarget = Files.readSymbolicLink(currentLink);
        Path resolvedTarget = linkTarget.isAbsolute()
                ? linkTarget
                : currentLink.getParent().resolve(linkTarget);
        resolvedTarget = resolvedTarget.normalize().toAbsolutePath();
        if (!Files.isDirectory(resolvedTarget)) {
            throw new IOException("Redis current指向的版本目录不存在");
        }
        return resolvedTarget;
    }

    /**
     * 清除首次激活前可能残留的旧版本记录。
     *
     * @throws IOException 删除状态文件失败时抛出
     */
    private void clearPreviousTarget() throws IOException {
        Files.deleteIfExists(getStateDirectory().resolve("previous-version-target"));
    }

    /**
     * 原子保存切换前的版本目录，供回滚操作使用。
     *
     * @param previousTarget current切换前指向的绝对目录
     * @throws IOException 创建状态目录或写入文件失败时抛出
     */
    private void writePreviousTarget(Path previousTarget) throws IOException {
        Path stateDirectory = getStateDirectory();
        Files.createDirectories(stateDirectory);
        Path temporaryFile = Files.createTempFile(
                stateDirectory,
                "previous-version-target-",
                ".tmp"
        );
        try {
            Files.write(
                    temporaryFile,
                    (previousTarget + System.lineSeparator()).getBytes(StandardCharsets.UTF_8)
            );
            moveReplacing(temporaryFile, stateDirectory.resolve("previous-version-target"));
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    /**
     * 切换current并在任何切换或校验失败时恢复切换前状态。
     *
     * @param previousTarget 切换前current指向的目录；首次激活时为null
     * @param targetDirectory 本次准备切换到的目录
     * @throws VersionSwitchFailedException 切换失败但旧链接已经恢复时抛出
     * @throws AutomaticRollbackFailedException 切换失败且旧链接也无法恢复时抛出
     */
    private void switchWithAutomaticRollback(Path previousTarget, Path targetDirectory)
            throws VersionSwitchFailedException, AutomaticRollbackFailedException {
        try {
            replaceCurrentLink(targetDirectory);
            verifyCurrentTarget(targetDirectory);
        } catch (IOException | UnsupportedOperationException switchException) {
            try {
                restorePreviousLinkState(previousTarget);
            } catch (IOException | UnsupportedOperationException rollbackException) {
                rollbackException.addSuppressed(switchException);
                throw new AutomaticRollbackFailedException(
                        "Redis版本切换失败，并且无法自动恢复切换前链接状态",
                        rollbackException
                );
            }
            throw new VersionSwitchFailedException(
                    previousTarget == null
                            ? "Redis首次激活失败，已删除未完成的current链接"
                            : "Redis版本切换失败，current已自动恢复到旧版本",
                    switchException
            );
        }
    }

    /**
     * 版本升级失败时恢复旧链接；首次激活失败时恢复为current不存在。
     *
     * @param previousTarget 旧版本目录；首次激活时为null
     * @throws IOException 无法恢复切换前状态时抛出
     */
    private void restorePreviousLinkState(Path previousTarget) throws IOException {
        if (previousTarget != null) {
            // 即使原子替换按设计应保持“旧或新”之一，也主动重建旧链接进行兜底。
            replaceCurrentLink(previousTarget);
            verifyCurrentTarget(previousTarget);
            return;
        }

        Path currentLink = getCurrentLink();
        Files.deleteIfExists(currentLink);
        if (Files.exists(currentLink, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("首次激活失败后无法删除current链接");
        }
    }

    /**
     * 创建指向指定目录的唯一临时软链接，再原子替换current。
     *
     * @param targetDirectory 新软链接需要指向的版本目录
     * @throws IOException 创建、移动或清理临时链接失败时抛出
     */
    private void replaceCurrentLink(Path targetDirectory) throws IOException {
        Path temporaryLink = getRedisDirectory().resolve(
                ".current-switch-" + UUID.randomUUID()
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
     * 重新读取current，确认软链接最终指向预期版本目录。
     *
     * @param expectedTarget 预期的绝对版本目录
     * @throws IOException current无法读取或指向错误目录时抛出
     */
    private void verifyCurrentTarget(Path expectedTarget) throws IOException {
        Path actualTarget = readCurrentTargetIfPresent();
        if (actualTarget == null) {
            throw new IOException("Redis current链接不存在");
        }
        if (!actualTarget.equals(expectedTarget.normalize().toAbsolutePath())) {
            throw new IOException(
                    "Redis current指向校验失败，实际目录: " + actualTarget
            );
        }
    }

    /**
     * 优先使用原子移动替换目标，文件系统不支持时退化为普通替换移动。
     *
     * @param source 临时文件或临时软链接
     * @param target 最终路径
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
     * 读取子进程输出。
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
     * 构造带统一时间范围的失败结果。
     *
     * @param errorCode 稳定错误分类
     * @param message 不包含密码或token的失败说明
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

    /** 表示平台要求的前置停止步骤尚未完成。 */
    private static class ServiceNotStoppedException extends Exception {
        /** @param message 未停止的具体服务状态 */
        ServiceNotStoppedException(String message) {
            super(message);
        }
    }

    /** 表示目标版本目录或程序不符合切换要求。 */
    private static class PackageValidationException extends Exception {
        /** @param message 具体校验失败原因 */
        PackageValidationException(String message) {
            super(message);
        }
    }

    /** 表示redis-server版本检查超过允许时间。 */
    private static class VersionCheckTimeoutException extends Exception {
        /** @param message 超时说明 */
        VersionCheckTimeoutException(String message) {
            super(message);
        }
    }

    /** 表示版本链接切换失败，但旧版本链接已经自动恢复。 */
    private static class VersionSwitchFailedException extends Exception {
        /**
         * @param message 切换失败及恢复结果说明
         * @param cause 原始切换异常
         */
        VersionSwitchFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 表示版本链接切换和自动恢复旧链接均失败，需要人工处理。 */
    private static class AutomaticRollbackFailedException extends Exception {
        /**
         * @param message 自动回滚失败说明
         * @param cause 原始回滚异常
         */
        AutomaticRollbackFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
