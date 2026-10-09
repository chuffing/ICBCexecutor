package com.danuvius.agent.configdeploy.service;

import com.danuvius.agent.config.AgentProperties;
import com.danuvius.agent.configdeploy.exception.ConfigPackageConflictException;
import com.danuvius.agent.configdeploy.model.ConfigTaskParameters;
import com.danuvius.agent.task.model.PlatformTask;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 接收并暂存平台审核过的Redis和Sentinel配置文件。
 */
@Service
public class ConfigPackageService {

    private static final int BUFFER_SIZE = 8192;

    @Autowired
    private AgentProperties properties;

    /**
     * 流式保存两份配置，校验摘要后以任务目录为单位安装。
     *
     * @param task 平台创建的DEPLOY_CONFIG任务
     * @param redisFile Redis配置文件
     * @param sentinelFile Sentinel配置文件
     * @throws IOException 读取上传内容或写入暂存目录失败时抛出
     */
    public void accept(PlatformTask task, MultipartFile redisFile,
                       MultipartFile sentinelFile) throws IOException {
        ConfigTaskParameters parameters = ConfigTaskParameters.from(task);
        validateUpload(redisFile, "Redis配置文件");
        validateUpload(sentinelFile, "Sentinel配置文件");

        Path incomingDirectory = getIncomingDirectory();
        Files.createDirectories(incomingDirectory);
        Path finalDirectory = incomingDirectory.resolve(parameters.getTaskNo()).normalize();
        if (!incomingDirectory.equals(finalDirectory.getParent())) {
            throw new IllegalArgumentException("配置暂存任务目录不合法");
        }

        if (Files.exists(finalDirectory)) {
            verifyExisting(finalDirectory, parameters);
            return;
        }

        Path stagingDirectory = Files.createTempDirectory(
                incomingDirectory,
                "." + parameters.getTaskNo() + "-"
        );
        try {
            String redisSha256 = saveAndCalculateSha256(
                    redisFile,
                    stagingDirectory.resolve("redis.conf")
            );
            String sentinelSha256 = saveAndCalculateSha256(
                    sentinelFile,
                    stagingDirectory.resolve("sentinel.conf")
            );
            if (!parameters.getRedisConfigSha256().equals(redisSha256)) {
                throw new IllegalArgumentException("Redis配置文件SHA-256与平台任务不一致");
            }
            if (!parameters.getSentinelConfigSha256().equals(sentinelSha256)) {
                throw new IllegalArgumentException("Sentinel配置文件SHA-256与平台任务不一致");
            }
            installDirectory(stagingDirectory, finalDirectory, parameters);
            stagingDirectory = null;
        } finally {
            if (stagingDirectory != null) {
                deleteRecursively(stagingDirectory);
            }
        }
    }

    /**
     * 检查上传文件和配置的大小上限。
     *
     * @param file 上传文件
     * @param label 文件显示名称
     */
    private void validateUpload(MultipartFile file, String label) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        long maximumBytes = properties.getConfigFileMaxBytes();
        if (maximumBytes <= 0) {
            throw new IllegalStateException("配置文件大小上限配置不正确");
        }
        if (file.getSize() > maximumBytes) {
            throw new IllegalArgumentException(label + "超过允许的大小上限");
        }
    }

    /**
     * 获取配置文件暂存根目录。
     *
     * @return 规范化后的绝对目录
     */
    private Path getIncomingDirectory() {
        String upgradeDirectory = properties.getUpgradeDir();
        if (upgradeDirectory == null || upgradeDirectory.trim().isEmpty()) {
            throw new IllegalStateException("升级临时目录配置不能为空");
        }
        return Paths.get(upgradeDirectory, "config-incoming")
                .toAbsolutePath()
                .normalize();
    }

    /**
     * 保存一个上传文件并计算实际摘要。
     *
     * @param file 上传文件
     * @param target 固定名称的暂存文件
     * @return 实际SHA-256
     * @throws IOException 读取或写入失败时抛出
     */
    private String saveAndCalculateSha256(MultipartFile file, Path target)
            throws IOException {
        MessageDigest digest = createSha256Digest();
        long maximumBytes = properties.getConfigFileMaxBytes();
        long totalBytes = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = new BufferedInputStream(file.getInputStream());
             OutputStream output = new BufferedOutputStream(Files.newOutputStream(target))) {
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                totalBytes += bytesRead;
                if (totalBytes > maximumBytes) {
                    throw new IllegalArgumentException("配置文件超过允许的大小上限");
                }
                digest.update(buffer, 0, bytesRead);
                output.write(buffer, 0, bytesRead);
            }
        }
        if (totalBytes == 0) {
            throw new IllegalArgumentException("配置文件不能为空");
        }
        return toHex(digest.digest());
    }

    /**
     * 以整个目录为单位安装两份已经校验的配置。
     *
     * @param stagingDirectory 临时任务目录
     * @param finalDirectory 正式任务目录
     * @param parameters 平台任务摘要
     * @throws IOException 移动或并发校验失败时抛出
     */
    private void installDirectory(Path stagingDirectory, Path finalDirectory,
                                  ConfigTaskParameters parameters) throws IOException {
        try {
            Files.move(stagingDirectory, finalDirectory, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(stagingDirectory, finalDirectory);
        } catch (FileAlreadyExistsException e) {
            verifyExisting(finalDirectory, parameters);
        }
    }

    /**
     * 判断重复上传是否与任务原先绑定的两份配置完全一致。
     *
     * @param directory 已存在的任务目录
     * @param parameters 当前任务摘要
     * @throws IOException 读取文件失败时抛出
     */
    private void verifyExisting(Path directory, ConfigTaskParameters parameters)
            throws IOException {
        Path redisFile = directory.resolve("redis.conf");
        Path sentinelFile = directory.resolve("sentinel.conf");
        if (Files.isRegularFile(redisFile) && Files.isRegularFile(sentinelFile)
                && parameters.getRedisConfigSha256().equals(calculateSha256(redisFile))
                && parameters.getSentinelConfigSha256().equals(calculateSha256(sentinelFile))) {
            return;
        }
        throw new ConfigPackageConflictException("同一任务编号已经上传了不同的配置文件");
    }

    /**
     * 计算本机文件SHA-256。
     *
     * @param file 待计算文件
     * @return 小写SHA-256
     * @throws IOException 读取失败时抛出
     */
    private String calculateSha256(Path file) throws IOException {
        MessageDigest digest = createSha256Digest();
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }
        return toHex(digest.digest());
    }

    /**
     * 创建SHA-256计算器。
     *
     * @return SHA-256计算器
     */
    private MessageDigest createSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前JVM不支持SHA-256", e);
        }
    }

    /**
     * 把摘要字节转换成小写十六进制字符串。
     *
     * @param values 摘要字节
     * @return 小写十六进制字符串
     */
    private String toHex(byte[] values) {
        StringBuilder result = new StringBuilder(values.length * 2);
        for (byte value : values) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    /**
     * 清理未完成的临时任务目录。
     *
     * @param directory 待删除目录
     * @throws IOException 删除失败时抛出
     */
    private void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                    throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
