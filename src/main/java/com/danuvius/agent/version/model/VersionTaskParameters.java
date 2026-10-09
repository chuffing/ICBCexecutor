package com.danuvius.agent.version.model;

import com.danuvius.agent.task.model.PlatformTask;
import com.danuvius.agent.common.operation.OperationType;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 平台版本包任务中允许使用的固定参数。
 */
public class VersionTaskParameters {

    private static final Pattern VERSION_PATTERN =
            Pattern.compile("[0-9]+(?:\\.[0-9]+){1,3}");
    private static final Pattern SHA256_PATTERN =
            Pattern.compile("[0-9a-fA-F]{64}");

    private final String taskNo;
    private final String version;
    private final String packageSha256;

    /**
     * 创建已经完成格式校验的版本任务参数对象。
     *
     * @param taskNo 业务任务编号
     * @param version 平台声明的 Redis 版本号
     * @param packageSha256 原始 tar.gz 文件的 SHA-256
     */
    private VersionTaskParameters(String taskNo, String version, String packageSha256) {
        this.taskNo = taskNo;
        this.version = version;
        this.packageSha256 = packageSha256;
    }

    /**
     * 校验平台任务类型，并读取 DEPLOY_VERSION 所需参数。
     *
     * @param task 平台下发的完整任务
     * @return 格式统一后的版本任务参数
     * @throws IllegalArgumentException 任务为空、编号为空或任务类型不正确时抛出
     */
    public static VersionTaskParameters from(PlatformTask task) {
        if (task == null) {
            throw new IllegalArgumentException("版本部署任务不能为空");
        }
        String taskNo = task.requireTaskNo();
        if (task.resolveOperationType() != OperationType.DEPLOY_VERSION) {
            throw new IllegalArgumentException("版本包接口只接受DEPLOY_VERSION任务");
        }
        return from(taskNo, task.getParams());
    }

    /**
     * 校验任务编号、版本号和版本包摘要。
     *
     * <p>版本号只允许数字和点，防止版本号被用来拼接出 releases 目录以外的路径；
     * SHA-256 必须是 64 位十六进制字符串。</p>
     *
     * @param taskNo 业务任务编号
     * @param params 任务参数，必须包含 version 和 packageSha256
     * @return 格式统一后的版本任务参数
     * @throws IllegalArgumentException 任一必填参数缺失或格式错误时抛出
     */
    public static VersionTaskParameters from(String taskNo, Map<String, String> params) {
        if (taskNo == null || taskNo.trim().isEmpty()) {
            throw new IllegalArgumentException("版本部署任务编号不能为空");
        }
        if (params == null) {
            throw new IllegalArgumentException("版本部署参数不能为空");
        }

        String version = trim(params.get("version"));
        if (version == null || !VERSION_PATTERN.matcher(version).matches()) {
            throw new IllegalArgumentException("Redis版本号格式不正确");
        }

        String packageSha256 = trim(params.get("packageSha256"));
        if (packageSha256 == null || !SHA256_PATTERN.matcher(packageSha256).matches()) {
            throw new IllegalArgumentException("版本包SHA-256格式不正确");
        }

        return new VersionTaskParameters(
                taskNo.trim(),
                version,
                // 摘要统一保存为小写，后续比较时不会受十六进制大小写影响。
                packageSha256.toLowerCase(Locale.ROOT)
        );
    }

    /**
     * 去掉字符串首尾空白，同时保留 null 语义。
     *
     * @param value 原始字符串
     * @return 去掉首尾空白的字符串；输入为 null 时仍返回 null
     */
    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    /** @return 业务任务编号 */
    public String getTaskNo() {
        return taskNo;
    }

    /** @return 平台声明并经过格式校验的 Redis 版本号 */
    public String getVersion() {
        return version;
    }

    /** @return 统一为小写的版本包 SHA-256 */
    public String getPackageSha256() {
        return packageSha256;
    }
}
