package com.icbc.nbfh.service.health;

import com.icbc.nbfh.entity.result.SentinelHealthResult;

import com.icbc.nbfh.config.AgentProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Sentinel轻量健康检查器。
 *
 * 每次调用只检查一次PING和指定MASTER的监控状态，不包含循环等待。
 */
@Component
public class SentinelHealthChecker {

    private static final Set<String> UNHEALTHY_FLAGS = new HashSet<>(Arrays.asList(
            "s_down",
            "o_down",
            "disconnected"
    ));

    @Autowired
    private AgentProperties properties;

    /**
     * 对配置中的Sentinel执行一次健康检查。
     */
    public SentinelHealthResult check() {
        AgentProperties.SentinelConfig sentinelConfig = properties.getSentinel();
        String validationError = validateConfig(sentinelConfig);
        if (validationError != null) {
            return SentinelHealthResult.unhealthy(validationError);
        }

        try (Jedis jedis = new Jedis(
                sentinelConfig.getHost(),
                sentinelConfig.getPort(),
                sentinelConfig.getTimeout())) {
            authenticateIfNecessary(jedis, sentinelConfig.getPassword());

            String pingResult = jedis.ping();
            if (!"PONG".equalsIgnoreCase(pingResult)) {
                return SentinelHealthResult.unhealthy("Sentinel PING响应异常");
            }

            Map<String, String> master = findMaster(
                    jedis.sentinelMasters(),
                    sentinelConfig.getMasterName()
            );
            if (master == null) {
                return SentinelHealthResult.unhealthy(
                        "Sentinel未监控指定MASTER: " + sentinelConfig.getMasterName()
                );
            }

            Set<String> flags = parseFlags(master.get("flags"));
            if (!flags.contains("master")) {
                return SentinelHealthResult.unhealthy("Sentinel监控对象不是MASTER");
            }
            for (String unhealthyFlag : UNHEALTHY_FLAGS) {
                if (flags.contains(unhealthyFlag)) {
                    return SentinelHealthResult.unhealthy(
                            "Sentinel监控状态异常: " + unhealthyFlag
                    );
                }
            }

            String masterHost = requireText(master.get("ip"), "Sentinel MASTER地址为空");
            int masterPort = parsePort(master.get("port"));
            return SentinelHealthResult.healthy(
                    sentinelConfig.getMasterName(),
                    masterHost,
                    masterPort
            );
        } catch (Exception e) {
            return SentinelHealthResult.unhealthy(buildFailureMessage(e));
        }
    }

    private String validateConfig(AgentProperties.SentinelConfig config) {
        if (config == null) {
            return "Sentinel配置不存在";
        }
        if (config.getHost() == null || config.getHost().trim().isEmpty()) {
            return "Sentinel地址不能为空";
        }
        if (config.getPort() <= 0 || config.getPort() > 65535) {
            return "Sentinel端口配置不正确";
        }
        if (config.getMasterName() == null || config.getMasterName().trim().isEmpty()) {
            return "Sentinel MASTER名称不能为空";
        }
        if (config.getTimeout() <= 0) {
            return "Sentinel连接超时时间必须大于0毫秒";
        }
        return null;
    }

    private void authenticateIfNecessary(Jedis jedis, String password) {
        if (password != null && !password.isEmpty()) {
            jedis.auth(password);
        }
    }

    private Map<String, String> findMaster(List<Map<String, String>> masters,
                                           String masterName) {
        if (masters == null) {
            return null;
        }
        for (Map<String, String> master : masters) {
            if (masterName.equals(master.get("name"))) {
                return master;
            }
        }
        return null;
    }

    private Set<String> parseFlags(String rawFlags) {
        Set<String> flags = new HashSet<>();
        if (rawFlags == null) {
            return flags;
        }
        for (String flag : rawFlags.split(",")) {
            String normalizedFlag = flag.trim().toLowerCase(Locale.ROOT);
            if (!normalizedFlag.isEmpty()) {
                flags.add(normalizedFlag);
            }
        }
        return flags;
    }

    private String requireText(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException(message);
        }
        return value.trim();
    }

    private int parsePort(String rawPort) {
        int port = Integer.parseInt(requireText(rawPort, "Sentinel MASTER端口为空"));
        if (port <= 0 || port > 65535) {
            throw new IllegalStateException("Sentinel MASTER端口不正确");
        }
        return port;
    }

    private String buildFailureMessage(Exception exception) {
        String detail = exception.getMessage();
        if (detail == null || detail.trim().isEmpty()) {
            return "Sentinel健康检查失败: " + exception.getClass().getSimpleName();
        }
        return "Sentinel健康检查失败: " + detail;
    }
}
