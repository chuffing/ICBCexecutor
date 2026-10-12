package com.icbc.nbfh.service.impl;

import com.icbc.nbfh.service.client.PlatformClient;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.service.ConfigSnapshotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;

/**
 * 执行器配置备份初始化和平台一致性校验实现。
 */
@Slf4j
@Service
public class ConfigSnapshotServiceImpl implements ConfigSnapshotService {

    @Autowired
    private AgentProperties properties;

    @Autowired
    private PlatformClient platformClient;

    /**
     * 查询平台状态，按是否已初始化决定能否创建本机备份，最后上传校验。
     */
    @Override
    public boolean initializeOrVerify(String agentId, String token) {
        try {
            Map<String, Object> status = platformClient.getConfigSnapshotStatus(
                    agentId, properties.getPlatformUrl(), token);
            boolean initialized = readInitialized(status);

            AgentProperties.RecoveryConfig recovery = requireRecoveryConfig();
            Path redisBackup = normalizedPath(recovery.getRedisBackupFile(), "Redis备份配置");
            Path sentinelBackup = normalizedPath(
                    recovery.getSentinelBackupFile(), "Sentinel备份配置");

            if (!initialized) {
                // 平台还没有基准时，本机备份不完整就从当前生效配置创建一对新备份。
                if (!Files.isRegularFile(redisBackup) || !Files.isRegularFile(sentinelBackup)) {
                    createInitialBackups(recovery, redisBackup, sentinelBackup);
                }
            } else {
                // 平台已有基准后绝不使用当前配置修补备份，缺失时必须人工处理。
                requireReadableBackup(redisBackup, "Redis备份配置");
                requireReadableBackup(sentinelBackup, "Sentinel备份配置");
                verifyLocalHashes(status, redisBackup, sentinelBackup);
            }

            byte[] redisContent = readConfig(redisBackup, "Redis备份配置");
            byte[] sentinelContent = readConfig(sentinelBackup, "Sentinel备份配置");
            Map<String, Object> response = platformClient.uploadConfigSnapshot(
                    agentId,
                    Base64.getEncoder().encodeToString(redisContent),
                    Base64.getEncoder().encodeToString(sentinelContent),
                    properties.getPlatformUrl(),
                    token);
            verifyLocalHashes(response, redisBackup, sentinelBackup);
            log.info("本机配置备份已与平台基准配置校验一致，agentId={}", agentId);
            return true;
        } catch (Exception e) {
            // 配置校验失败不能阻止心跳，但故障恢复会因摘要不一致而拒绝使用该备份。
            log.error("配置备份初始化或平台一致性校验失败，禁止使用该备份执行配置操作", e);
            return false;
        }
    }

    /** 判断平台状态响应中是否已经存在基准配置。 */
    private boolean readInitialized(Map<String, Object> response) {
        Map<?, ?> data = requireData(response);
        Object initialized = data.get("initialized");
        if (!(initialized instanceof Boolean)) {
            throw new IllegalStateException("平台配置快照状态缺少initialized字段");
        }
        return (Boolean) initialized;
    }

    /** 从当前生效配置原子生成首次本机备份。 */
    private void createInitialBackups(AgentProperties.RecoveryConfig recovery,
                                      Path redisBackup,
                                      Path sentinelBackup) throws IOException {
        Path redisTarget = normalizedPath(recovery.getRedisTargetFile(), "Redis生效配置");
        Path sentinelTarget = normalizedPath(
                recovery.getSentinelTargetFile(), "Sentinel生效配置");
        requireReadableBackup(redisTarget, "Redis生效配置");
        requireReadableBackup(sentinelTarget, "Sentinel生效配置");

        Path redisTemp = prepareTempCopy(redisTarget, redisBackup, "redis-config-init-");
        Path sentinelTemp = prepareTempCopy(
                sentinelTarget, sentinelBackup, "sentinel-config-init-");
        try {
            moveReplacing(redisTemp, redisBackup);
            redisTemp = null;
            moveReplacing(sentinelTemp, sentinelBackup);
            sentinelTemp = null;
            log.info("首次注册已创建Redis和Sentinel本机备份配置");
        } finally {
            if (redisTemp != null) {
                Files.deleteIfExists(redisTemp);
            }
            if (sentinelTemp != null) {
                Files.deleteIfExists(sentinelTemp);
            }
        }
    }

    /** 把一个生效配置复制为备份目录中的临时文件。 */
    private Path prepareTempCopy(Path source, Path target, String prefix) throws IOException {
        Path parent = target.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("备份配置必须有父目录: " + target);
        }
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, prefix, ".tmp");
        Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
        return temp;
    }

    /** 优先使用原子移动替换备份文件，文件系统不支持时退化为普通替换。 */
    private void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 校验本机两个备份文件与平台返回的可信摘要一致。 */
    private void verifyLocalHashes(Map<String, Object> response,
                                   Path redisBackup,
                                   Path sentinelBackup) throws IOException {
        Map<?, ?> data = requireData(response);
        String expectedRedis = requireHash(data, "redisConfigSha256");
        String expectedSentinel = requireHash(data, "sentinelConfigSha256");
        String actualRedis = calculateSha256(readConfig(redisBackup, "Redis备份配置"));
        String actualSentinel = calculateSha256(readConfig(sentinelBackup, "Sentinel备份配置"));
        if (!expectedRedis.equals(actualRedis)) {
            throw new IllegalStateException("本机Redis备份配置与平台基准配置不一致");
        }
        if (!expectedSentinel.equals(actualSentinel)) {
            throw new IllegalStateException("本机Sentinel备份配置与平台基准配置不一致");
        }
    }

    /** 读取平台统一响应中的data对象。 */
    private Map<?, ?> requireData(Map<String, Object> response) {
        if (response == null || !(response.get("data") instanceof Map)) {
            throw new IllegalStateException("平台配置快照响应缺少data对象");
        }
        return (Map<?, ?>) response.get("data");
    }

    /** 读取平台返回的64位SHA-256。 */
    private String requireHash(Map<?, ?> data, String fieldName) {
        Object value = data.get(fieldName);
        if (!(value instanceof String)
                || !((String) value).matches("[0-9a-fA-F]{64}")) {
            throw new IllegalStateException("平台配置快照响应缺少合法的" + fieldName);
        }
        return ((String) value).toLowerCase();
    }

    /** 读取单个配置文件并执行大小限制。 */
    private byte[] readConfig(Path path, String label) throws IOException {
        requireReadableBackup(path, label);
        long size = Files.size(path);
        if (size <= 0 || size > properties.getConfigFileMaxBytes()) {
            throw new IllegalArgumentException(
                    label + "大小必须在1到" + properties.getConfigFileMaxBytes() + "字节之间");
        }
        return Files.readAllBytes(path);
    }

    /** 确认配置文件存在并且可读。 */
    private void requireReadableBackup(Path path, String label) {
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IllegalStateException(label + "不存在或不可读取: " + path);
        }
    }

    /** 计算配置原始字节的SHA-256。 */
    private String calculateSha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest) {
                hex.append(String.format("%02x", value & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前JVM不支持SHA-256", e);
        }
    }

    /** 获取并校验故障恢复路径配置。 */
    private AgentProperties.RecoveryConfig requireRecoveryConfig() {
        AgentProperties.RecoveryConfig recovery = properties.getRecovery();
        if (recovery == null) {
            throw new IllegalArgumentException("故障恢复路径配置不能为空");
        }
        return recovery;
    }

    /** 把配置值转换为规范化绝对路径。 */
    private Path normalizedPath(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + "路径不能为空");
        }
        return Paths.get(value.trim()).toAbsolutePath().normalize();
    }
}
