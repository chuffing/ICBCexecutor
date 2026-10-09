package com.danuvius.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    /** 平台地址 */
    private String platformUrl;

    /** 预设注册码 */
    private String registerCode;

    /** Agent 名称 */
    private String agentName;

    /** 采集间隔（毫秒） */
    private long collectInterval = 300000;

    /** 心跳间隔（毫秒） */
    private long heartbeatInterval = 30000;

    /** Agent安装根目录。 */
    private String agentRoot = "/opt/redis-monitor-agent";

    /** 只允许执行固定脚本的目录。 */
    private String scriptDir = "/opt/redis-monitor-agent/scripts";

    /** 版本切换和回滚状态的持久化目录。 */
    private String stateDir = "/opt/redis-monitor-agent/state";

    /** 本地 Redis 配置 */
    private RedisConfig redis = new RedisConfig();

    /** 本地 Sentinel 配置 */
    private SentinelConfig sentinel = new SentinelConfig();

    /** 故障恢复使用的备份文件和目标配置文件。 */
    private RecoveryConfig recovery = new RecoveryConfig();

    /** Token 持久化文件 */
    private String tokenFile = "./agent-token.dat";

    /** 升级包临时目录 */
    private String upgradeDir = "/tmp/redis-upgrade/";

    /** 单个Redis版本包允许的最大字节数，默认512 MiB。 */
    private long versionPackageMaxBytes = 536870912L;

    /** 单个Redis或Sentinel配置文件允许的最大字节数，默认10 MiB。 */
    private long configFileMaxBytes = 10485760L;

    @Data
    public static class SentinelConfig {
        /** Sentinel systemd服务名。 */
        private String serviceName = "redis-sentinel.service";
        private String host = "127.0.0.1";
        private int port = 26379;
        private String password;
        private String masterName = "mymaster";
        private int timeout = 2000;
    }

    @Data
    public static class RedisConfig {
        /** Redis安装根目录。 */
        private String rootDir = "/opt/redis";

        /** 不同Redis版本的安装目录。 */
        private String releasesDir = "/opt/redis/releases";

        /** 当前启用版本的软链接路径。 */
        private String currentLink = "/opt/redis/current";

        /** Redis和Sentinel配置文件目录。 */
        private String configDir = "/opt/redis/config";





        /** Redis数据目录。 */
        private String dataDir = "/opt/redis/data";

        /** Redis systemd服务名。 */
        private String serviceName = "redis-server.service";

        private String host = "127.0.0.1";
        private int port = 6379;
        private String password;
        private int db = 0;
        private int timeout = 5000;
        private Pool pool = new Pool();

        @Data
        public static class Pool {
            private int maxTotal = 5;
            private int maxIdle = 2;
            private int minIdle = 1;
            private long maxWaitMillis = 2000;
        }
    }

    @Data
    public static class RecoveryConfig {
        /** 独立备份位置中的 Redis 配置文件。 */
        private String redisBackupFile = "/backup/redis-config/redis.conf.bak";

        /** 独立备份位置中的 Sentinel 配置文件。 */
        private String sentinelBackupFile = "/backup/redis-config/sentinel.conf.bak";

        /** Redis 服务实际读取的配置文件。 */
        private String redisTargetFile = "/etc/redis/redis.conf";

        /** Sentinel 服务实际读取的配置文件。 */
        private String sentinelTargetFile = "/etc/redis/sentinel.conf";
    }
}
