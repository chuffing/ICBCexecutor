package com.danuvius.agent.configdeploy.operation;

import com.danuvius.agent.common.operation.OperationErrorCode;
import com.danuvius.agent.common.operation.OperationResult;
import com.danuvius.agent.config.AgentProperties;
import com.danuvius.agent.configdeploy.model.ConfigTaskParameters;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
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
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 把平台审核并暂存的Redis和Sentinel配置原子部署到本机。
 */
@Component
public class DeployConfigOperation {

    private static final int BUFFER_SIZE = 8192;
    private static final int PORT_CHECK_TIMEOUT_MILLIS = 500;
    private static final long COMMAND_TIMEOUT_SECONDS = 5;

    @Autowired
    private AgentProperties properties;

    /**
     * 校验暂存文件和本机停止状态，再替换两份正式配置。
     *
     * @param taskNo 业务任务编号
     * @param params 两份配置文件的SHA-256
     * @return 统一操作结果
     */
    public OperationResult execute(String taskNo, Map<String, String> params) {
        long startTime = System.currentTimeMillis();
        try {
            ConfigTaskParameters parameters = ConfigTaskParameters.from(taskNo, params);
            Path taskDirectory = getTaskDirectory(parameters.getTaskNo());
            Path redisSource = taskDirectory.resolve("redis.conf");
            Path sentinelSource = taskDirectory.resolve("sentinel.conf");
            validateSource(redisSource, parameters.getRedisConfigSha256(), "Redis");
            validateSource(sentinelSource, parameters.getSentinelConfigSha256(), "Sentinel");

            // 平台负责跨服务器顺序，Agent在本机再次确认服务和端口均已停止。
            verifyLocalServicesStopped();

            AgentProperties.RecoveryConfig recovery = requireRecoveryConfig();
            Path redisTarget = requireAbsolutePath(
                    recovery.getRedisTargetFile(),
                    "Redis目标配置"
            );
            Path sentinelTarget = requireAbsolutePath(
                    recovery.getSentinelTargetFile(),
                    "Sentinel目标配置"
            );
            replaceBothWithRollback(
                    redisSource,
                    redisTarget,
                    parameters.getRedisConfigSha256(),
                    sentinelSource,
                    sentinelTarget,
                    parameters.getSentinelConfigSha256()
            );

            return OperationResult.success(
                    "平台审核的Redis和Sentinel配置已部署",
                    startTime,
                    System.currentTimeMillis()
            );
        } catch (IllegalArgumentException e) {
            return failure(OperationErrorCode.CONFIG_INVALID, e.getMessage(), startTime);
        } catch (ServiceNotStoppedException e) {
            return failure(OperationErrorCode.PRECONDITION_FAILED, e.getMessage(), startTime);
        } catch (ConfigRollbackFailedException e) {
            return failure(OperationErrorCode.CONFIG_ROLLBACK_FAILED, e.getMessage(), startTime);
        } catch (ConfigDeployFailedException e) {
            return failure(OperationErrorCode.CONFIG_DEPLOY_FAILED, e.getMessage(), startTime);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure(OperationErrorCode.UNKNOWN_ERROR, "配置部署检查被中断", startTime);
        } catch (IOException | UnsupportedOperationException e) {
            return failure(
                    OperationErrorCode.CONFIG_DEPLOY_FAILED,
                    "配置文件读取、校验或部署失败",
                    startTime
            );
        }
    }

    /**
     * 获取以taskNo隔离的配置暂存目录。
     *
     * @param taskNo 业务任务编号
     * @return 规范化的任务目录
     */
    private Path getTaskDirectory(String taskNo) {
        String upgradeDirectory = properties.getUpgradeDir();
        if (upgradeDirectory == null || upgradeDirectory.trim().isEmpty()) {
            throw new IllegalArgumentException("升级临时目录配置不能为空");
        }
        Path incoming = Paths.get(upgradeDirectory, "config-incoming")
                .toAbsolutePath()
                .normalize();
        Path taskDirectory = incoming.resolve(taskNo).normalize();
        if (!incoming.equals(taskDirectory.getParent())) {
            throw new IllegalArgumentException("配置暂存任务目录不合法");
        }
        return taskDirectory;
    }

    /**
     * 确认暂存配置存在、非空且摘要未发生变化。
     *
     * @param source 暂存配置
     * @param expectedSha256 平台任务摘要
     * @param label 配置显示名称
     * @throws IOException 读取文件失败时抛出
     */
    private void validateSource(Path source, String expectedSha256, String label)
            throws IOException {
        if (!Files.isRegularFile(source) || Files.size(source) == 0) {
            throw new IllegalArgumentException(label + "暂存配置不存在或为空");
        }
        if (!expectedSha256.equals(calculateSha256(source))) {
            throw new IllegalArgumentException(label + "暂存配置SHA-256与任务不一致");
        }
    }

    /**
     * 获取故障恢复与配置部署共用的正式配置路径。
     *
     * @return 配置路径对象
     */
    private AgentProperties.RecoveryConfig requireRecoveryConfig() {
        AgentProperties.RecoveryConfig recovery = properties.getRecovery();
        if (recovery == null) {
            throw new IllegalArgumentException("正式配置文件路径不能为空");
        }
        return recovery;
    }

    /**
     * 校验配置文件路径必须是绝对路径。
     *
     * @param value 原始路径
     * @param label 配置名称
     * @return 规范化的绝对路径
     */
    private Path requireAbsolutePath(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + "路径不能为空");
        }
        Path path = Paths.get(value.trim());
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException(label + "必须使用绝对路径");
        }
        return path.normalize();
    }

    /**
     * 确认本机Redis和Sentinel服务停止且端口释放。
     *
     * @throws IOException 无法读取服务或网络状态时抛出
     * @throws InterruptedException 等待systemctl时被中断
     * @throws ServiceNotStoppedException 任一服务或端口仍在运行
     */
    private void verifyLocalServicesStopped()
            throws IOException, InterruptedException, ServiceNotStoppedException {
        AgentProperties.RedisConfig redis = properties.getRedis();
        AgentProperties.SentinelConfig sentinel = properties.getSentinel();
        if (redis == null || sentinel == null) {
            throw new IllegalArgumentException("Redis或Sentinel配置不能为空");
        }
        verifyServiceStopped(redis.getServiceName(), redis.getHost(), redis.getPort(), "Redis");
        verifyServiceStopped(
                sentinel.getServiceName(),
                sentinel.getHost(),
                sentinel.getPort(),
                "Sentinel"
        );
    }

    /**
     * 检查单个systemd服务和监听端口。
     *
     * @param serviceName systemd服务名
     * @param host 本机检查地址
     * @param port 服务端口
     * @param label 服务显示名称
     */
    private void verifyServiceStopped(String serviceName, String host, int port, String label)
            throws IOException, InterruptedException, ServiceNotStoppedException {
        if (serviceName == null || serviceName.trim().isEmpty()) {
            throw new IllegalArgumentException(label + "服务名不能为空");
        }
        if (host == null || host.trim().isEmpty() || port <= 0 || port > 65535) {
            throw new IllegalArgumentException(label + "地址或端口配置不正确");
        }
        String state = readSystemdState(serviceName.trim());
        if (!("inactive".equals(state) || "failed".equals(state))) {
            throw new ServiceNotStoppedException(label + "服务尚未停止: " + state);
        }
        if (isPortOpen(host.trim(), port)) {
            throw new ServiceNotStoppedException(label + "端口仍在监听: " + host + ":" + port);
        }
    }

    /**
     * 读取systemd服务活动状态。
     *
     * @param serviceName systemd服务名
     * @return systemctl状态文本
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
     * 尝试连接端口，确认是否仍有进程监听。
     *
     * @param host 本机服务地址
     * @param port 服务端口
     * @return 端口开放或无法保守确认关闭时返回true
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
     * 准备两份新文件后依次原子替换，任一失败时恢复两份原文件。
     */
    private void replaceBothWithRollback(
            Path redisSource, Path redisTarget, String redisSha256,
            Path sentinelSource, Path sentinelTarget, String sentinelSha256)
            throws IOException, ConfigDeployFailedException, ConfigRollbackFailedException {
        Replacement redis = prepareReplacement(redisSource, redisTarget);
        Replacement sentinel = null;
        try {
            sentinel = prepareReplacement(sentinelSource, sentinelTarget);
            try {
                installReplacement(redis);
                installReplacement(sentinel);
                verifyInstalled(redisTarget, redisSha256, "Redis");
                verifyInstalled(sentinelTarget, sentinelSha256, "Sentinel");
            } catch (IOException | UnsupportedOperationException deployException) {
                try {
                    rollbackReplacement(sentinel);
                    rollbackReplacement(redis);
                } catch (IOException rollbackException) {
                    redis.preserveFiles = true;
                    sentinel.preserveFiles = true;
                    rollbackException.addSuppressed(deployException);
                    throw new ConfigRollbackFailedException(
                            "配置部署失败，并且无法完整恢复原配置",
                            rollbackException
                    );
                }
                throw new ConfigDeployFailedException(
                        "配置部署失败，原配置已恢复",
                        deployException
                );
            }
        } finally {
            cleanupReplacement(sentinel);
            cleanupReplacement(redis);
        }
    }

    /**
     * 在目标目录创建新配置临时文件，并备份原配置。
     *
     * @param source 已审核的暂存配置
     * @param target 正式配置路径
     * @return 本次文件替换状态
     */
    private Replacement prepareReplacement(Path source, Path target) throws IOException {
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IOException("目标配置目录不存在: " + target);
        }
        if (Files.exists(target) && !Files.isRegularFile(target)) {
            throw new IOException("目标配置不是普通文件: " + target);
        }

        Replacement replacement = new Replacement(target, Files.isRegularFile(target));
        replacement.newFile = Files.createTempFile(parent, ".config-new-", ".tmp");
        Files.copy(source, replacement.newFile, StandardCopyOption.REPLACE_EXISTING);
        applyPermissions(replacement.newFile, target, replacement.originalExisted);

        if (replacement.originalExisted) {
            replacement.backupFile = Files.createTempFile(parent, ".config-old-", ".tmp");
            Files.copy(
                    target,
                    replacement.backupFile,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES
            );
        }
        return replacement;
    }

    /**
     * 新文件沿用原配置权限；首次部署时使用0644。
     *
     * @param newFile 新配置临时文件
     * @param target 正式配置路径
     * @param originalExisted 是否存在原配置
     */
    private void applyPermissions(Path newFile, Path target, boolean originalExisted)
            throws IOException {
        Set<PosixFilePermission> permissions = originalExisted
                ? Files.getPosixFilePermissions(target)
                : PosixFilePermissions.fromString("rw-r--r--");
        Files.setPosixFilePermissions(newFile, permissions);
    }

    /**
     * 原子安装一份已经准备好的新配置。
     *
     * @param replacement 文件替换状态
     */
    private void installReplacement(Replacement replacement) throws IOException {
        moveReplacing(replacement.newFile, replacement.target);
        replacement.newFile = null;
        replacement.installed = true;
    }

    /**
     * 恢复一份替换前的配置或删除首次部署的新文件。
     *
     * @param replacement 文件替换状态
     */
    private void rollbackReplacement(Replacement replacement) throws IOException {
        if (replacement == null || !replacement.installed) {
            return;
        }
        if (replacement.originalExisted) {
            moveReplacing(replacement.backupFile, replacement.target);
            replacement.backupFile = null;
        } else {
            Files.deleteIfExists(replacement.target);
        }
        replacement.installed = false;
    }

    /**
     * 验证正式配置摘要与平台任务一致。
     *
     * @param target 正式配置文件
     * @param expectedSha256 平台摘要
     * @param label 配置显示名称
     */
    private void verifyInstalled(Path target, String expectedSha256, String label)
            throws IOException {
        if (!Files.isRegularFile(target) || !expectedSha256.equals(calculateSha256(target))) {
            throw new IOException(label + "正式配置部署后摘要校验失败");
        }
    }

    /**
     * 清理未使用的新文件和原配置备份。
     *
     * @param replacement 文件替换状态
     */
    private void cleanupReplacement(Replacement replacement) {
        if (replacement == null) {
            return;
        }
        if (replacement.preserveFiles) {
            // 回滚失败时保留新旧临时文件，方便人工确认并恢复。
            return;
        }
        try {
            if (replacement.newFile != null) {
                Files.deleteIfExists(replacement.newFile);
            }
            if (replacement.backupFile != null) {
                Files.deleteIfExists(replacement.backupFile);
            }
        } catch (IOException ignored) {
            // 正式部署结果优先，残留的隐藏临时文件交由人工或定期清理。
        }
    }

    /**
     * 使用原子移动替换目标；文件系统不支持时退化为普通替换移动。
     *
     * @param source 临时文件
     * @param target 正式文件
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
     * 计算文件SHA-256。
     *
     * @param file 待计算文件
     * @return 小写SHA-256
     */
    private String calculateSha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前JVM不支持SHA-256", e);
        }
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    /**
     * 读取子进程全部输出。
     *
     * @param input 子进程输出流
     * @return UTF-8文本
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
     * 构造统一失败结果。
     *
     * @param errorCode 稳定错误码
     * @param message 结果说明
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

    /** 保存一份文件原子替换过程中需要的临时状态。 */
    private static class Replacement {
        private final Path target;
        private final boolean originalExisted;
        private Path newFile;
        private Path backupFile;
        private boolean installed;
        private boolean preserveFiles;

        /**
         * @param target 正式配置路径
         * @param originalExisted 是否存在原配置
         */
        Replacement(Path target, boolean originalExisted) {
            this.target = target;
            this.originalExisted = originalExisted;
        }
    }

    /** 表示本机服务或端口尚未停止。 */
    private static class ServiceNotStoppedException extends Exception {
        /** @param message 未满足的停止条件 */
        ServiceNotStoppedException(String message) {
            super(message);
        }
    }

    /** 表示配置部署失败但原配置已经恢复。 */
    private static class ConfigDeployFailedException extends Exception {
        /**
         * @param message 失败及恢复结果
         * @param cause 原始部署异常
         */
        ConfigDeployFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 表示部署失败且无法完整恢复原配置。 */
    private static class ConfigRollbackFailedException extends Exception {
        /**
         * @param message 严重失败说明
         * @param cause 原始回滚异常
         */
        ConfigRollbackFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
