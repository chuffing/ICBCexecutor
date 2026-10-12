package com.icbc.nbfh.service.health;

import com.icbc.nbfh.entity.result.RedisHealthResult;

import com.icbc.nbfh.config.AgentProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.commands.ProtocolCommand;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Redis 轻量健康检查器。
 *
 * <p>每次调用执行一次 PING 和 ROLE；如果实际角色是 REPLICA，继续读取
 * 当前主库地址、端口及复制链路状态，供启动操作校验平台期望的拓扑。</p>
 */
@Component
public class RedisHealthChecker {

    /** Jedis 3.6.3 未提供公开的 role() 方法，因此使用固定的底层 ROLE 命令。 */
    private static final ProtocolCommand ROLE_COMMAND =
            () -> "ROLE".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private AgentProperties properties;

    /**
     * 对配置中的 Redis 执行一次健康检查。
     *
     * @return 包含角色及复制拓扑的单次检查结果
     */
    public RedisHealthResult check() {
        AgentProperties.RedisConfig redisConfig = properties.getRedis();
        if (redisConfig == null) {
            return RedisHealthResult.unhealthy("Redis配置不存在");
        }

        try (Jedis jedis = new Jedis(
                redisConfig.getHost(),
                redisConfig.getPort(),
                redisConfig.getTimeout())) {
            authenticateIfNecessary(jedis, redisConfig.getPassword());
            jedis.select(redisConfig.getDb());

            String pingResult = jedis.ping();
            if (!"PONG".equalsIgnoreCase(pingResult)) {
                return RedisHealthResult.unhealthy("Redis PING响应异常");
            }

            String role = readBusinessRole(jedis.sendCommand(ROLE_COMMAND));
            if ("MASTER".equals(role)) {
                return RedisHealthResult.healthyMaster();
            }

            return checkReplicaLink(jedis);
        } catch (Exception e) {
            return RedisHealthResult.unhealthy(buildFailureMessage(e));
        }
    }

    /**
     * 读取 REPLICA 的主库地址、端口和连接状态。
     *
     * @param jedis 已认证的 Redis 连接
     * @return 包含复制拓扑的健康结果
     */
    private RedisHealthResult checkReplicaLink(Jedis jedis) {
        String replicationInfo = jedis.info("replication");
        String rawStatus = readInfoValue(replicationInfo, "master_link_status");
        String masterHost = readInfoValue(replicationInfo, "master_host");
        Integer masterPort = parseOptionalPort(
                readInfoValue(replicationInfo, "master_port")
        );

        if (masterHost == null || masterHost.trim().isEmpty() || masterPort == null) {
            return RedisHealthResult.replicationUnhealthy(
                    normalize(rawStatus),
                    masterHost,
                    masterPort,
                    "Redis REPLICA复制信息缺少master_host或master_port"
            );
        }
        if (rawStatus == null || rawStatus.trim().isEmpty()) {
            return RedisHealthResult.replicationUnhealthy(
                    null,
                    masterHost,
                    masterPort,
                    "Redis REPLICA复制信息缺少master_link_status"
            );
        }

        String normalizedStatus = normalize(rawStatus);
        if (!"UP".equals(normalizedStatus)) {
            return RedisHealthResult.replicationUnhealthy(
                    normalizedStatus,
                    masterHost,
                    masterPort,
                    "Redis REPLICA尚未连接MASTER: master_link_status=" + normalizedStatus
            );
        }
        return RedisHealthResult.healthyReplica(
                normalizedStatus,
                masterHost,
                masterPort
        );
    }

    /**
     * Redis 配置了密码时执行认证。
     *
     * @param jedis Redis 连接
     * @param password Redis 密码
     */
    private void authenticateIfNecessary(Jedis jedis, String password) {
        if (password != null && !password.isEmpty()) {
            jedis.auth(password);
        }
    }

    /**
     * 将 ROLE 命令的底层返回值转换为业务角色。
     *
     * @param rawResponse ROLE 原始响应
     * @return MASTER 或 REPLICA
     */
    private String readBusinessRole(Object rawResponse) {
        if (!(rawResponse instanceof List)) {
            throw new IllegalStateException("Redis ROLE响应格式不正确");
        }

        List<?> roleResponse = (List<?>) rawResponse;
        if (roleResponse.isEmpty()) {
            throw new IllegalStateException("Redis ROLE响应为空");
        }

        Object rawRole = roleResponse.get(0);
        String redisRole;
        if (rawRole instanceof byte[]) {
            redisRole = new String((byte[]) rawRole, StandardCharsets.UTF_8);
        } else {
            redisRole = String.valueOf(rawRole);
        }

        String normalizedRole = redisRole.trim().toLowerCase(Locale.ROOT);
        if ("master".equals(normalizedRole)) {
            return "MASTER";
        }
        if ("slave".equals(normalizedRole) || "replica".equals(normalizedRole)) {
            return "REPLICA";
        }
        throw new IllegalStateException("Redis ROLE响应不支持: " + normalizedRole);
    }

    /**
     * 从 INFO 文本中读取指定字段。
     *
     * @param info INFO 命令文本
     * @param key 字段名
     * @return 字段值，不存在时返回 null
     */
    private String readInfoValue(String info, String key) {
        if (info == null) {
            return null;
        }
        String prefix = key + ":";
        for (String line : info.split("\\r?\\n")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    /**
     * 解析 INFO 中的可选端口。
     *
     * @param rawPort 端口文本
     * @return 有效端口；缺失或格式错误时返回 null
     */
    private Integer parseOptionalPort(String rawPort) {
        if (rawPort == null || rawPort.trim().isEmpty()) {
            return null;
        }
        try {
            int port = Integer.parseInt(rawPort.trim());
            return port > 0 && port <= 65535 ? port : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 将状态文本统一为大写。
     *
     * @param value 原始状态
     * @return 大写状态；输入为空时返回 null
     */
    private String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 生成不包含密码的异常说明。
     *
     * @param exception 健康检查异常
     * @return 可安全记录的错误消息
     */
    private String buildFailureMessage(Exception exception) {
        String detail = exception.getMessage();
        if (detail == null || detail.trim().isEmpty()) {
            return "Redis健康检查失败: " + exception.getClass().getSimpleName();
        }
        return "Redis健康检查失败: " + detail;
    }
}
