package com.danuvius.agent.configdeploy.model;

import com.danuvius.agent.common.operation.OperationType;
import com.danuvius.agent.task.model.PlatformTask;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 平台配置部署任务中允许使用的固定参数。
 */
public class ConfigTaskParameters {

    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-fA-F]{64}");

    private final String taskNo;
    private final String redisConfigSha256;
    private final String sentinelConfigSha256;

    /**
     * 创建已经完成格式校验的配置部署参数。
     *
     * @param taskNo 业务任务编号
     * @param redisConfigSha256 Redis配置文件SHA-256
     * @param sentinelConfigSha256 Sentinel配置文件SHA-256
     */
    private ConfigTaskParameters(String taskNo, String redisConfigSha256,
                                 String sentinelConfigSha256) {
        this.taskNo = taskNo;
        this.redisConfigSha256 = redisConfigSha256;
        this.sentinelConfigSha256 = sentinelConfigSha256;
    }

    /**
     * 校验上传接口收到的任务类型和参数。
     *
     * @param task 平台发送的完整任务
     * @return 格式统一后的配置部署参数
     */
    public static ConfigTaskParameters from(PlatformTask task) {
        if (task == null) {
            throw new IllegalArgumentException("配置部署任务不能为空");
        }
        String taskNo = task.requireTaskNo();
        if (task.resolveOperationType() != OperationType.DEPLOY_CONFIG) {
            throw new IllegalArgumentException("配置上传接口只接受DEPLOY_CONFIG任务");
        }
        return from(taskNo, task.getParams());
    }

    /**
     * 校验执行阶段使用的两份配置摘要。
     *
     * @param taskNo 已校验的业务任务编号
     * @param params 任务参数
     * @return 格式统一后的配置部署参数
     */
    public static ConfigTaskParameters from(String taskNo, Map<String, String> params) {
        if (taskNo == null || taskNo.trim().isEmpty()) {
            throw new IllegalArgumentException("配置部署任务编号不能为空");
        }
        if (params == null) {
            throw new IllegalArgumentException("配置部署参数不能为空");
        }
        String redisSha256 = requireSha256(params.get("redisConfigSha256"), "redisConfigSha256");
        String sentinelSha256 = requireSha256(
                params.get("sentinelConfigSha256"),
                "sentinelConfigSha256"
        );
        return new ConfigTaskParameters(taskNo.trim(), redisSha256, sentinelSha256);
    }

    /**
     * 校验一个SHA-256参数并统一为小写。
     *
     * @param value 原始参数值
     * @param name 参数名称
     * @return 小写SHA-256
     */
    private static String requireSha256(String value, String name) {
        if (value == null || !SHA256_PATTERN.matcher(value.trim()).matches()) {
            throw new IllegalArgumentException("配置摘要格式不正确: " + name);
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    /** @return 业务任务编号 */
    public String getTaskNo() {
        return taskNo;
    }

    /** @return Redis配置文件SHA-256 */
    public String getRedisConfigSha256() {
        return redisConfigSha256;
    }

    /** @return Sentinel配置文件SHA-256 */
    public String getSentinelConfigSha256() {
        return sentinelConfigSha256;
    }
}
