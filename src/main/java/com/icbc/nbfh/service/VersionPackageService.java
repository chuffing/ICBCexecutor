package com.icbc.nbfh.service;

import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.dto.PlatformTask;
import com.icbc.nbfh.common.exception.VersionPackageConflictException;
import com.icbc.nbfh.entity.params.VersionTaskParameters;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 接收平台直接上传的Redis版本包。
 */
@Service
public class VersionPackageService {

    private static final int BUFFER_SIZE = 8192;

    @Autowired
    private AgentProperties properties;

    /**
     * 将版本包流式保存为固定任务文件，并在接收过程中同步计算 SHA-256。
     *
     * <p>先写入 .part 临时文件，只有大小和摘要都通过校验后才改名为正式文件。
     * 这样网络中断或校验失败时不会留下一个看似完整的版本包。</p>
     *
     * @param task 平台创建的 DEPLOY_VERSION 任务
     * @param file 平台上传的 tar.gz 文件
     * @throws IOException 读取上传内容或写入本机文件失败时抛出
     * @throws IllegalArgumentException 任务参数、文件大小或摘要不合法时抛出
     */
    public void accept(PlatformTask task, MultipartFile file) throws IOException {
        VersionTaskParameters parameters = VersionTaskParameters.from(task);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("版本包不能为空");
        }

        long maximumBytes = properties.getVersionPackageMaxBytes();
        if (maximumBytes <= 0) {
            throw new IllegalStateException("版本包大小上限配置不正确");
        }
        // Content-Length 只能用于提前拒绝，流式读取时还会再次累计字节数。
        if (file.getSize() > maximumBytes) {
            throw new IllegalArgumentException("版本包超过允许的大小上限");
        }

        Path incomingDirectory = getIncomingDirectory();
        Files.createDirectories(incomingDirectory);
        Path finalPackage = incomingDirectory.resolve(
                parameters.getTaskNo() + ".tar.gz"
        );
        Path temporaryPackage = Files.createTempFile(
                incomingDirectory,
                parameters.getTaskNo() + "-",
                ".part"
        );

        try {
            // 保存和计算摘要在同一次读取中完成，避免大文件被完整读取两遍。
            String actualSha256 = saveAndCalculateSha256(
                    file,
                    temporaryPackage,
                    maximumBytes
            );
            if (!parameters.getPackageSha256().equals(actualSha256)) {
                throw new IllegalArgumentException("版本包SHA-256与平台任务不一致");
            }

            installPackage(temporaryPackage, finalPackage, actualSha256);
        } finally {
            // 校验失败、保存失败或安装成功后，都不应残留 .part 临时文件。
            Files.deleteIfExists(temporaryPackage);
        }
    }

    /**
     * 获取版本包接收目录。
     *
     * @return 绝对化并规范化后的 incoming 目录
     * @throws IllegalStateException 未配置升级临时目录时抛出
     */
    private Path getIncomingDirectory() {
        String upgradeDirectory = properties.getUpgradeDir();
        if (upgradeDirectory == null || upgradeDirectory.trim().isEmpty()) {
            throw new IllegalStateException("版本包临时目录配置不能为空");
        }
        return Paths.get(upgradeDirectory, "incoming").toAbsolutePath().normalize();
    }

    /**
     * 把上传内容流式写入临时文件，同时计算实际 SHA-256。
     *
     * @param file Spring 接收到的上传文件
     * @param target 本机 .part 临时文件
     * @param maximumBytes 允许接收的最大字节数
     * @return 上传内容的 SHA-256 小写十六进制字符串
     * @throws IOException 读取或写入失败时抛出
     */
    private String saveAndCalculateSha256(MultipartFile file, Path target, long maximumBytes)
            throws IOException {
        MessageDigest digest = createSha256Digest();
        long totalBytes = 0;
        byte[] buffer = new byte[BUFFER_SIZE];

        try (InputStream input = new BufferedInputStream(file.getInputStream());
             OutputStream output = new BufferedOutputStream(Files.newOutputStream(target))) {
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                totalBytes += bytesRead;
                // 即使请求头伪造了较小的文件大小，也不能绕过真正的流式大小限制。
                if (totalBytes > maximumBytes) {
                    throw new IllegalArgumentException("版本包超过允许的大小上限");
                }
                digest.update(buffer, 0, bytesRead);
                output.write(buffer, 0, bytesRead);
            }
        }
        return toHex(digest.digest());
    }

    /**
     * 把已校验的临时包安装为以 taskNo 命名的正式包。
     *
     * <p>相同 taskNo 和相同摘要视为重复提交并直接成功；相同 taskNo 对应不同内容时
     * 拒绝覆盖，避免重试请求悄悄替换任务原本绑定的版本包。</p>
     *
     * @param temporaryPackage 已校验的 .part 文件
     * @param finalPackage 最终版本包路径
     * @param actualSha256 临时包的实际 SHA-256
     * @throws IOException 移动或读取文件失败时抛出
     */
    private void installPackage(Path temporaryPackage, Path finalPackage,
                                String actualSha256) throws IOException {
        if (Files.exists(finalPackage)) {
            if (actualSha256.equals(calculateSha256(finalPackage))) {
                return;
            }
            throw new VersionPackageConflictException(
                    "同一任务编号已经上传了不同的版本包"
            );
        }

        try {
            // 优先使用原子移动，使其他线程只能看到完整文件或看不到文件。
            Files.move(temporaryPackage, finalPackage, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporaryPackage, finalPackage);
        } catch (FileAlreadyExistsException e) {
            // 两个请求并发写同一 taskNo 时，再次比较最终文件决定幂等成功还是冲突。
            if (!actualSha256.equals(calculateSha256(finalPackage))) {
                throw new VersionPackageConflictException(
                        "同一任务编号并发上传了不同的版本包"
                );
            }
        }
    }

    /**
     * 计算本机文件的 SHA-256。
     *
     * @param file 需要计算摘要的文件
     * @return SHA-256 小写十六进制字符串
     * @throws IOException 读取文件失败时抛出
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
     * 创建 SHA-256 摘要计算器。
     *
     * @return 当前 JVM 提供的 SHA-256 计算器
     * @throws IllegalStateException 当前 JVM 不支持 SHA-256 时抛出
     */
    private MessageDigest createSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前JVM不支持SHA-256", e);
        }
    }

    /**
     * 把摘要字节数组转换为固定长度的小写十六进制字符串。
     *
     * @param values 摘要原始字节
     * @return 小写十六进制字符串
     */
    private String toHex(byte[] values) {
        StringBuilder hex = new StringBuilder(values.length * 2);
        for (byte value : values) {
            hex.append(String.format("%02x", value & 0xff));
        }
        return hex.toString();
    }
}
