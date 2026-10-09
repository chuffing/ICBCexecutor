package com.danuvius.agent.recovery.operation;

import com.danuvius.agent.common.operation.OperationErrorCode;
import com.danuvius.agent.common.operation.OperationResult;
import com.danuvius.agent.common.command.CommandResult;
import com.danuvius.agent.common.command.FixedScriptExecutor;
import com.danuvius.agent.config.AgentProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 使用独立备份磁盘上的已验证文件恢复本机Redis和Sentinel配置。
 */
@Component
public class RestoreConfigOperation {

    private static final String RESTORE_SCRIPT = "restore-config.sh";
    private static final long SCRIPT_TIMEOUT_SECONDS = 10;
    private static final String REDIS_SHA256_PARAM = "redisConfigSha256";
    private static final String SENTINEL_SHA256_PARAM = "sentinelConfigSha256";
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-fA-F]{64}");

    @Autowired
    private FixedScriptExecutor fixedScriptExecutor;

    @Autowired
    private AgentProperties properties;

    /**
     * 验证备份文件与平台摘要一致后，执行固定恢复脚本。
     */
    public OperationResult execute(Map<String, String> params) {
        long startTime = System.currentTimeMillis();

        String expectedRedisSha256;
        String expectedSentinelSha256;
        try {
            expectedRedisSha256 = requireSha256(params, REDIS_SHA256_PARAM);
            expectedSentinelSha256 = requireSha256(params, SENTINEL_SHA256_PARAM);
        } catch (IllegalArgumentException e) {
            return OperationResult.failure(
                    OperationErrorCode.CONFIG_INVALID,
                    e.getMessage(),
                    startTime,
                    System.currentTimeMillis()
            );
        }

        try {
            AgentProperties.RecoveryConfig recovery = requireRecoveryConfig();
            File redisBackup = requireConfiguredFile(
                    recovery.getRedisBackupFile(),
                    "Redis备份配置"
            );
            File sentinelBackup = requireConfiguredFile(
                    recovery.getSentinelBackupFile(),
                    "Sentinel备份配置"
            );

            String actualRedisSha256 = calculateSha256(redisBackup);
            if (!expectedRedisSha256.equals(actualRedisSha256)) {
                return OperationResult.failure(
                        OperationErrorCode.CHECKSUM_MISMATCH,
                        "备份磁盘中的redis.conf与平台配置不一致",
                        startTime,
                        System.currentTimeMillis()
                );
            }

            String actualSentinelSha256 = calculateSha256(sentinelBackup);
            if (!expectedSentinelSha256.equals(actualSentinelSha256)) {
                return OperationResult.failure(
                        OperationErrorCode.CHECKSUM_MISMATCH,
                        "备份磁盘中的sentinel.conf与平台配置不一致",
                        startTime,
                        System.currentTimeMillis()
                );
            }

            CommandResult commandResult = fixedScriptExecutor.execute(
                    RESTORE_SCRIPT,
                    SCRIPT_TIMEOUT_SECONDS
            );
            if (commandResult.isTimedOut()) {
                return OperationResult.failure(
                        OperationErrorCode.COMMAND_TIMEOUT,
                        "配置恢复脚本执行超过10秒",
                        startTime,
                        System.currentTimeMillis()
                );
            }
            if (commandResult.getExitCode() != 0) {
                return OperationResult.failure(
                        OperationErrorCode.COMMAND_EXECUTION_FAILED,
                        "配置恢复脚本执行失败，退出码: " + commandResult.getExitCode(),
                        startTime,
                        System.currentTimeMillis()
                );
            }

            return OperationResult.success(
                    "Redis和Sentinel配置已通过平台摘要校验并恢复",
                    startTime,
                    System.currentTimeMillis()
            );
        } catch (IllegalArgumentException e) {
            return OperationResult.failure(
                    OperationErrorCode.CONFIG_INVALID,
                    e.getMessage(),
                    startTime,
                    System.currentTimeMillis()
            );
        } catch (FileNotFoundException e) {
            return OperationResult.failure(
                    OperationErrorCode.COMMAND_NOT_FOUND,
                    e.getMessage(),
                    startTime,
                    System.currentTimeMillis()
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return OperationResult.failure(
                    OperationErrorCode.UNKNOWN_ERROR,
                    "配置恢复操作被中断",
                    startTime,
                    System.currentTimeMillis()
            );
        } catch (IOException e) {
            return OperationResult.failure(
                    OperationErrorCode.COMMAND_EXECUTION_FAILED,
                    "读取备份配置或执行恢复脚本失败",
                    startTime,
                    System.currentTimeMillis()
            );
        } catch (Exception e) {
            return OperationResult.failure(
                    OperationErrorCode.UNKNOWN_ERROR,
                    "配置恢复发生未知错误",
                    startTime,
                    System.currentTimeMillis()
            );
        }
    }

    /**
     * 读取故障恢复配置，并确认配置对象存在。
     *
     * @return 已绑定的故障恢复配置
     */
    private AgentProperties.RecoveryConfig requireRecoveryConfig() {
        AgentProperties.RecoveryConfig recovery = properties.getRecovery();
        if (recovery == null) {
            throw new IllegalArgumentException("故障恢复路径配置不能为空");
        }
        return recovery;
    }

    /**
     * 把配置文件路径转换为规范化绝对文件。
     *
     * @param value 配置的文件路径
     * @param label 错误信息中的配置名称
     * @return 规范化后的绝对文件
     */
    private File requireConfiguredFile(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + "路径不能为空");
        }
        return new File(value.trim()).toPath().toAbsolutePath().normalize().toFile();
    }

    /**
     * 读取并校验平台下发的 SHA-256 参数。
     *
     * @param params 平台任务参数
     * @param key 需要读取的参数名称
     * @return 统一转换成小写的 SHA-256
     */
    private String requireSha256(Map<String, String> params, String key) {
        if (params == null) {
            throw new IllegalArgumentException("配置恢复参数不能为空");
        }
        String value = params.get(key);
        if (value == null || !SHA256_PATTERN.matcher(value.trim()).matches()) {
            throw new IllegalArgumentException("配置恢复参数不合法: " + key);
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 计算本机备份配置文件的 SHA-256。
     *
     * @param file 待校验的备份配置文件
     * @return 小写十六进制 SHA-256
     * @throws IOException 文件不存在、不可读或读取失败时抛出
     */
    private String calculateSha256(File file) throws IOException {
        if (!file.isFile() || !file.canRead()) {
            throw new FileNotFoundException("备份配置不存在或不可读取: " + file.getPath());
        }

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前JVM不支持SHA-256", e);
        }

        byte[] buffer = new byte[8192];
        try (InputStream inputStream = new FileInputStream(file)) {
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }

        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) {
            hex.append(String.format("%02x", value & 0xff));
        }
        return hex.toString();
    }
}
