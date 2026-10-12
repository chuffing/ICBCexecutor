package com.icbc.nbfh.service.operation;

import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.common.constant.OperationErrorCode;
import com.icbc.nbfh.entity.result.OperationResult;
import com.icbc.nbfh.entity.params.VersionTaskParameters;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 校验并部署Redis版本包，但不切换current链接。
 */
@Component
public class DeployVersionOperation {

    private static final int BUFFER_SIZE = 8192;
    private static final long MAX_EXTRACTED_BYTES = 2L * 1024 * 1024 * 1024;
    private static final int MAX_ARCHIVE_ENTRIES = 100000;
    private static final long VERSION_CHECK_TIMEOUT_SECONDS = 5;

    @Autowired
    private AgentProperties properties;

    /**
     * 校验并部署平台已经上传完成的 Redis 版本包。
     *
     * <p>本操作只把新版本安装到 releases 目录，不修改 current 链接，也不重启 Redis。
     * 因此“上传版本”和后续“切换版本”可以由平台分成两个独立步骤调度。</p>
     *
     * @param taskNo 业务任务编号，也是 incoming 版本包的文件名
     * @param params 任务参数，必须包含 version 和 packageSha256
     * @return 包含成功状态、错误码和说明的统一操作结果
     */
    public OperationResult execute(String taskNo, Map<String, String> params) {
        long startTime = System.currentTimeMillis();
        // 先创建一个临时解压目录
        Path stagingDirectory = null;

        try {
            VersionTaskParameters parameters = VersionTaskParameters.from(taskNo, params);
            Path packageFile = getPackageFile(parameters.getTaskNo());
            if (!Files.isRegularFile(packageFile)) {
                return failure(
                        OperationErrorCode.PACKAGE_INVALID,
                        "未找到已上传的版本包",
                        startTime
                );
            }

            // 执行前重新计算本机文件摘要，防止文件在接收完成后被替换或损坏。
            String actualSha256 = calculateSha256(packageFile);
            if (!parameters.getPackageSha256().equals(actualSha256)) {
                return failure(
                        OperationErrorCode.CHECKSUM_MISMATCH,
                        "待部署版本包SHA-256与平台任务不一致",
                        startTime
                );
            }

            Path releasesDirectory = getReleasesDirectory();
            Files.createDirectories(releasesDirectory);
            Path targetDirectory = releasesDirectory.resolve(
                    parameters.getVersion()
            ).normalize();

            // 即使前面的版本号正则以后被改动，这里仍阻止路径逃出 releases 目录。
            // 应该不会有人改的吧（？
            if (!targetDirectory.getParent().equals(releasesDirectory)) {
                return failure(
                        OperationErrorCode.PACKAGE_INVALID,
                        "版本目录不合法",
                        startTime
                );
            }

            // 1.不存在的话，继续部署
            // 2.已部署的并且是同一个包直接返回成功
            // 3.不同包则拒绝覆盖现有版本目录。
            OperationResult existingResult = checkExistingVersion(
                    targetDirectory,
                    parameters,
                    startTime
            );
            if (existingResult != null) {
                return existingResult;
            }

            // 先在 releases 下解压到临时目录，全部校验通过后再整体移动到正式目录。
            stagingDirectory = Files.createTempDirectory(
                    releasesDirectory,
                    "." + parameters.getTaskNo() + "-"
            );
            // 把版本包安全解压进刚创建的临时目录。
            extractArchive(packageFile, stagingDirectory);

            // 找到版本包中真正包含bin的根目录
            Path payloadDirectory = findPayloadDirectory(stagingDirectory);

            // 对刚解压出来的Redis程序做最后一次真实性和可执行性检查。
            validateRequiredPrograms(payloadDirectory, parameters.getVersion());

            // 摘要标记用于后续重试时判断是否确实为同一个版本包。
            Files.write(
                    payloadDirectory.resolve(".package.sha256"),
                    (parameters.getPackageSha256() + System.lineSeparator())
                            .getBytes(StandardCharsets.UTF_8)
            );

            moveVersionDirectory(payloadDirectory, targetDirectory);
            if (!stagingDirectory.equals(payloadDirectory)) {
                deleteRecursively(stagingDirectory);
            }
            stagingDirectory = null;

            return OperationResult.success(
                    "Redis版本已部署: " + parameters.getVersion(),
                    startTime,
                    System.currentTimeMillis()
            );
        } catch (IllegalArgumentException e) {
            return failure(OperationErrorCode.CONFIG_INVALID, e.getMessage(), startTime);
        } catch (VersionCheckTimeoutException e) {
            return failure(OperationErrorCode.COMMAND_TIMEOUT, e.getMessage(), startTime);
        } catch (PackageValidationException e) {
            return failure(OperationErrorCode.PACKAGE_INVALID, e.getMessage(), startTime);
        } catch (IOException e) {
            return failure(
                    OperationErrorCode.PACKAGE_INVALID,
                    "版本包读取、解压或安装失败",
                    startTime
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure(
                    OperationErrorCode.UNKNOWN_ERROR,
                    "版本程序检查被中断",
                    startTime
            );
        } catch (Exception e) {
            return failure(
                    OperationErrorCode.UNKNOWN_ERROR,
                    "版本部署发生未知错误",
                    startTime
            );
        } finally {
            if (stagingDirectory != null) {
                try {
                    // 任何失败都清理未完成的解压目录，避免它被误认为可用版本。
                    deleteRecursively(stagingDirectory);
                } catch (IOException ignored) {
                    // 原始部署结果优先，残留临时目录交由后续清理。
                }
            }
        }
    }

    /**
     * 读取并规范化 Redis 版本安装目录。
     *
     * @return 配置的 releases 绝对路径
     */
    private Path getReleasesDirectory() {
        AgentProperties.RedisConfig redis = properties.getRedis();
        String configuredPath = redis == null ? null : redis.getReleasesDir();
        if (configuredPath == null || configuredPath.trim().isEmpty()) {
            throw new IllegalArgumentException("Redis版本目录配置不能为空");
        }
        return Paths.get(configuredPath).toAbsolutePath().normalize();
    }

    /**
     * 判断目标版本目录是否已经存在，并处理重复提交。
     *
     * @param targetDirectory 以版本号命名的正式目录
     * @param parameters 当前任务参数
     * @param startTime 操作开始时间
     * @return 目录不存在时返回 null；已存在时返回幂等成功或冲突失败结果
     * @throws IOException 读取摘要标记失败时抛出
     */
    private OperationResult checkExistingVersion(Path targetDirectory,
                                                 VersionTaskParameters parameters,
                                                 long startTime) throws IOException {
        if (!Files.exists(targetDirectory)) {
            return null;
        }

        Path marker = targetDirectory.resolve(".package.sha256");
        if (Files.isRegularFile(marker)) {
            String recordedSha256 = new String(
                    Files.readAllBytes(marker),
                    StandardCharsets.UTF_8
            ).trim();
            if (parameters.getPackageSha256().equalsIgnoreCase(recordedSha256)) {
                return OperationResult.success(
                        "Redis版本已经部署: " + parameters.getVersion(),
                        startTime,
                        System.currentTimeMillis()
                );
            }
        }
        return failure(
                OperationErrorCode.PACKAGE_INVALID,
                "目标版本目录已存在且不属于当前版本包",
                startTime
        );
    }

    /**
     * 根据 taskNo 定位接收阶段保存的原始版本包。
     *
     * @param taskNo 业务任务编号
     * @return incoming 目录中的绝对版本包路径
     */
    private Path getPackageFile(String taskNo) {
        String upgradeDirectory = properties.getUpgradeDir();
        if (upgradeDirectory == null || upgradeDirectory.trim().isEmpty()) {
            throw new IllegalArgumentException("版本包临时目录配置不能为空");
        }
        return Paths.get(upgradeDirectory, "incoming", taskNo + ".tar.gz")
                .toAbsolutePath()
                .normalize();
    }

    /**
     * 安全地把 tar.gz 版本包解压到临时目录。
     *
     * <p>解压时限制文件数量和总字节数，拒绝链接、特殊文件、绝对路径和目录越界路径，
     * 防止恶意压缩包覆盖临时目录之外的系统文件。</p>
     *
     * @param packageFile 待解压的 tar.gz 文件
     * @param destination 本次任务的临时解压目录
     * @throws IOException 读取压缩包或写入文件失败时抛出
     * @throws PackageValidationException 压缩包结构或内容不安全时抛出
     */
    private void extractArchive(Path packageFile, Path destination)
            throws IOException, PackageValidationException {
        // 到目前为止解压了多少字节，最开始为0
        long totalBytes = 0;
        // 已经从tar包里读到了多少条目
        int entryCount = 0;
        byte[] buffer = new byte[BUFFER_SIZE];

        // try结束后，会自动关闭三个文件流
        // .tar.gz其实是.tar经过.gzip压缩之后得到的
        // 对于硬盘文件，我们要进行：1.缓冲读取 2.解gzip 3.解析tar
        try (InputStream fileInput = new BufferedInputStream(Files.newInputStream(packageFile)); // 打开这个File，数据“流入”java程序
             GzipCompressorInputStream gzipInput = new GzipCompressorInputStream(fileInput); // gzip解压器
             TarArchiveInputStream tarInput = new TarArchiveInputStream(gzipInput)) { // tar读取器
            // 当前从tar包里读取到的那个文件
            TarArchiveEntry entry;
            while ((entry = tarInput.getNextTarEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ARCHIVE_ENTRIES) {
                    throw new PackageValidationException("版本包文件数量超过限制");
                }
                if (entry.isSymbolicLink() || entry.isLink()) {
                    // 不管是硬链接还是软链接，都可能在后续写文件时把目标引向临时目录之外，因此一律拒绝。Redis版本包根本没有必要带有链接
                    throw new PackageValidationException("版本包不允许包含链接文件");
                }

                // 取出当前条目（entry）的路径字符串
                String entryName = entry.getName();
                if (entryName == null || entryName.trim().isEmpty()
                        || entryName.startsWith("/") || entryName.contains("\\")) {
                    throw new PackageValidationException("版本包包含不合法路径");
                }
                Path outputPath = destination.resolve(entryName).normalize();
                // normalize 会消除 ..，再用 startsWith 确认最终路径仍位于目标目录内。
                if (!outputPath.startsWith(destination)) {
                    throw new PackageValidationException("版本包路径越界");
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(outputPath);
                    continue; // 如果当前entry是文件夹，就创建一个文件夹，本次循环结束
                }
                if (!entry.isFile()) {
                    throw new PackageValidationException("版本包包含不支持的文件类型");
                }

                long entrySize = entry.getSize();
                // 依据 tar 条目声明的大小累计，限制解压炸弹占满磁盘。
                if (entrySize < 0 || totalBytes + entrySize > MAX_EXTRACTED_BYTES) {
                    throw new PackageValidationException("版本包解压后大小超过限制");
                }
                totalBytes += entrySize;
                Files.createDirectories(outputPath.getParent());

                // 开始写文件，刚才是硬盘到java，现在是java到硬盘
                try (OutputStream output = new BufferedOutputStream(
                        Files.newOutputStream(outputPath))) {
                    long remaining = entrySize;
                    while (remaining > 0) {
                        int bytesRead = tarInput.read(
                                buffer,
                                0,
                                (int) Math.min(buffer.length, remaining)
                        );
                        if (bytesRead < 0) {
                            throw new PackageValidationException("版本包内容不完整");
                        }
                        output.write(buffer, 0, bytesRead);
                        remaining -= bytesRead;
                    }
                }
            }
        }
        if (entryCount == 0) {
            throw new PackageValidationException("版本包为空");
        }
    }

    /**
     * 找到版本包中真正包含 bin 目录的根目录。
     *
     * <p>同时兼容“bin 直接位于压缩包根目录”和“压缩包只有一个顶层目录”两种结构。</p>
     *
     * @param stagingDirectory 临时解压目录
     * @return 包含 bin 目录的版本根目录
     * @throws IOException 遍历目录失败时抛出
     * @throws PackageValidationException 目录结构不符合约定时抛出
     */
    private Path findPayloadDirectory(Path stagingDirectory)
            throws IOException, PackageValidationException {
        if (Files.isDirectory(stagingDirectory.resolve("bin"))) {
            return stagingDirectory;
        }

        Path candidate = null;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(stagingDirectory)) {
            for (Path entry : entries) {
                if (!Files.isDirectory(entry)) {
                    throw new PackageValidationException("版本包目录结构不正确");
                }
                if (candidate != null) {
                    throw new PackageValidationException("版本包只能包含一个顶层目录");
                }
                candidate = entry;
            }
        }
        if (candidate == null || !Files.isDirectory(candidate.resolve("bin"))) {
            throw new PackageValidationException("版本包缺少bin目录");
        }
        return candidate;
    }

    /**
     * 校验版本包中的必要程序、执行权限和 Redis 实际版本。
     *
     * @param payloadDirectory 版本包根目录
     * @param version 平台任务声明的 Redis 版本号
     * @throws IOException 启动或读取版本检查进程失败时抛出
     * @throws InterruptedException 等待版本检查进程时线程被中断
     * @throws PackageValidationException 缺少程序或实际版本与任务不一致
     * @throws VersionCheckTimeoutException 版本检查进程超过规定时间
     */
    private void validateRequiredPrograms(Path payloadDirectory, String version)
            throws IOException, InterruptedException,
            PackageValidationException, VersionCheckTimeoutException {
        Path binDirectory = payloadDirectory.resolve("bin");
        // 检查三个文件是否真的存在
        Path redisServer = requireRegularFile(binDirectory.resolve("redis-server"));
        Path redisCli = requireRegularFile(binDirectory.resolve("redis-cli"));
        Path redisSentinel = requireRegularFile(binDirectory.resolve("redis-sentinel"));

        // 存在，并且要可执行
        makeExecutable(redisServer);
        makeExecutable(redisCli);
        makeExecutable(redisSentinel);

        // 文件名和压缩包名都可能伪造，因此运行二进制自身来确认实际 Redis 版本。
        Process process = new ProcessBuilder(redisServer.toString(), "--version")
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(VERSION_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(1, TimeUnit.SECONDS);
            throw new VersionCheckTimeoutException("redis-server版本检查超过5秒");
        }
        String output = readProcessOutput(process.getInputStream());
        if (process.exitValue() != 0 || !output.contains("v=" + version)) {
            throw new PackageValidationException("redis-server版本与任务参数不一致");
        }
    }

    /**
     * 要求指定路径必须是普通文件。
     *
     * @param file 版本包内必须存在的程序路径
     * @return 原路径，便于调用方继续使用
     * @throws PackageValidationException 文件缺失或不是普通文件时抛出
     */
    private Path requireRegularFile(Path file) throws PackageValidationException {
        if (!Files.isRegularFile(file)) {
            throw new PackageValidationException("版本包缺少程序: " + file.getFileName());
        }
        return file;
    }

    /**
     * 为解压后的 Redis 程序设置可执行权限。
     *
     * @param file 需要设置权限的程序
     * @throws PackageValidationException 当前用户无法设置执行权限时抛出
     */
    private void makeExecutable(Path file) throws PackageValidationException {
        if (!file.toFile().setExecutable(true, false)) {
            throw new PackageValidationException("无法设置程序执行权限: " + file.getFileName());
        }
    }

    /**
     * 读取子进程的全部输出。
     *
     * @param input 子进程标准输出流
     * @return UTF-8 字符串
     * @throws IOException 读取输出失败时抛出
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
     * 将校验通过的版本目录整体移动为正式目录。
     *
     * @param source 临时版本目录
     * @param target 最终 releases/版本号 目录
     * @throws IOException 移动失败时抛出
     */
    private void moveVersionDirectory(Path source, Path target) throws IOException {
        try {
            // 原子移动可以避免其他进程看到只复制了一部分的版本目录。
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    /**
     * 计算文件的 SHA-256，用于执行前再次确认版本包未发生变化。
     *
     * @param file 待校验文件
     * @return SHA-256 小写十六进制字符串
     * @throws IOException 读取文件失败时抛出
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

        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) {
            hex.append(String.format("%02x", value & 0xff));
        }
        return hex.toString();
    }

    /**
     * 递归删除临时目录及其全部内容。
     *
     * @param root 需要清理的根目录
     * @throws IOException 遍历或删除失败时抛出
     */
    private void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            /** 删除遍历到的普通文件。 */
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            /** 在目录内容全部删除后，再删除空目录本身。 */
            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception)
                    throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * 构造统一的失败结果并记录操作起止时间。
     *
     * @param errorCode 稳定的错误分类
     * @param message 不包含密码或 token 的失败说明
     * @param startTime 操作开始时间
     * @return 失败的 OperationResult
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

    /** 表示版本包内容或目录结构不符合部署要求。 */
    private static class PackageValidationException extends Exception {
        /** @param message 具体校验失败原因 */
        PackageValidationException(String message) {
            super(message);
        }
    }

    /** 表示执行 redis-server --version 超过允许时间。 */
    private static class VersionCheckTimeoutException extends Exception {
        /** @param message 超时说明 */
        VersionCheckTimeoutException(String message) {
            super(message);
        }
    }
}
