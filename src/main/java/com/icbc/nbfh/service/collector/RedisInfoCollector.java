package com.icbc.nbfh.service.collector;

import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.dto.MetricData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.HashMap;
import java.util.Map;

/**
 * Redis 指标采集器 - 通过 INFO 命令只读采集，不影响生产
 */
@Slf4j
@Component
public class RedisInfoCollector {

    private JedisPool jedisPool;

    // 上一次采样的累计值（用于计算差值）
    private double lastUsedCpuSys = 0;
    private double lastUsedCpuUser = 0;
    private long lastTotalCommands = 0;

    /** 采样窗口，200ms 足够接近瞬时，又不至于因时钟粒度抖动 */
    private static final long SAMPLE_WINDOW_MS = 200;

    /** 逻辑核数，启动时取一次即可 */
    private static final int CPU_CORES = Runtime.getRuntime().availableProcessors();


    private long lastCmdCalls = 0;
    private long lastCmdUsec = 0;
    private boolean hasLastSample = false;

    @Autowired
    private AgentProperties properties;

    public void initPool() {
        if (jedisPool != null) return;
        AgentProperties.RedisConfig rc = properties.getRedis();
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(rc.getPool().getMaxTotal());
        config.setMaxIdle(rc.getPool().getMaxIdle());
        config.setMinIdle(rc.getPool().getMinIdle());
        config.setMaxWaitMillis(rc.getPool().getMaxWaitMillis());
        String pwd = (rc.getPassword() == null || rc.getPassword().isEmpty()) ? null : rc.getPassword();
        jedisPool = new JedisPool(config, rc.getHost(), rc.getPort(),
                rc.getTimeout(), pwd, rc.getDb());
        log.info("Redis 连接池初始化完成: {}:{}", rc.getHost(), rc.getPort());
    }

    /**
     * 采集Redis状态；第一次失败时重建连接池并重试一次。
     *
     * <p>Redis服务重启后，连接池中可能残留重启前建立的TCP连接。第一次读取会出现
     * Unexpected end of stream，此时重建连接池可以使用新连接完成真实健康检查。</p>
     */
    public synchronized MetricData collectWithReconnect() {
        MetricData firstResult = collect();
        if ("up".equalsIgnoreCase(firstResult.getStatus())) {
            return firstResult;
        }

        log.info("Redis首次采集失败，重建连接池后重试一次: {}", firstResult.getErrorMsg());
        resetPool();
        return collect();
    }

    /** 关闭并清空旧连接池，使下一次采集建立全新的Redis连接。 */
    private void resetPool() {
        if (jedisPool != null) {
            try {
                jedisPool.close();
            } catch (Exception e) {
                log.warn("关闭旧Redis连接池失败", e);
            } finally {
                jedisPool = null;
            }
        }
    }

    /**
     * 执行一次采集
     */
    public MetricData collect() {
        MetricData data = new MetricData();
        data.setCollectTime(System.currentTimeMillis());
        try {
            initPool();
            try (Jedis jedis = jedisPool.getResource()) {
                String pingResult = jedis.ping();
                if (!"PONG".equalsIgnoreCase(pingResult)) {
                    throw new IllegalStateException("Redis PING 响应异常: " + pingResult);
                }

                String infoAll = jedis.info("all");
                String infoStats = jedis.info("stats");
                String infoReplication = jedis.info("replication");
                String infoServer = jedis.info("server");
                String cmdStats = jedis.info("commandstats");

                Map<String, String> all = parseInfo(infoAll);
                Map<String, String> stats = parseInfo(infoStats);
                Map<String, String> repl = parseInfo(infoReplication);
                Map<String, String> server = parseInfo(infoServer);

                // 1. CPU 占用率
                long t1 = System.nanoTime();
                Map<String, String> cpu1 = parseInfo(jedis.info("cpu"));
                double sys1  = getDouble(cpu1.get("used_cpu_sys"));
                double user1 = getDouble(cpu1.get("used_cpu_user"));

                try {
                    Thread.sleep(SAMPLE_WINDOW_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                long t2 = System.nanoTime();
                Map<String, String> cpu2 = parseInfo(jedis.info("cpu"));
                double sys2  = getDouble(cpu2.get("used_cpu_sys"));
                double user2 = getDouble(cpu2.get("used_cpu_user"));

                double elapsedSec = (t2 - t1) / 1_000_000_000.0;
                // 关键：除以逻辑核数，口径与任务管理器一致
                double cpuPer = ((sys2 - sys1) + (user2 - user1))
                        / elapsedSec
                        / CPU_CORES
                        * 100.0;

                // 保留 1 位小数
                data.setCpuPer(String.format("%.2f", cpuPer));
//                double usedCpuSys = getDouble(all.get("used_cpu_sys"));
//                double usedCpuUser = getDouble(all.get("used_cpu_user"));
//                if (hasLastSample) {
//                    double deltaSys = usedCpuSys - lastUsedCpuSys;
//                    double deltaUser = usedCpuUser - lastUsedCpuUser;
//                    double elapsedSec = properties.getCollectInterval() / 1000.0;
//                    double cpuPer = (deltaSys + deltaUser) / elapsedSec * 100;
//                    data.setCpuPer(String.format("%.2f", cpuPer));
//                } else {
//                    data.setCpuPer("0.00");
//                }
//                lastUsedCpuSys = usedCpuSys;
//                lastUsedCpuUser = usedCpuUser;

                // 2. 服务器内存占用率
                long usedMemRss = getLong(all.get("used_memory_rss"));
                String totalSysMemStr = all.get("total_system_memory");
                if (totalSysMemStr == null || totalSysMemStr.isEmpty()) {
                    totalSysMemStr = all.get("total_system_memory_human");
                }
                if (totalSysMemStr != null && !totalSysMemStr.isEmpty()) {
                    long totalSysMem = parseMemHuman(totalSysMemStr);
                    if (totalSysMem > 0) {
                        data.setMemServerPer(String.format("%.2f", (double) usedMemRss / totalSysMem * 100));
                    }
                }
                // Redis 内存使用量
                data.setMemUs(all.get("used_memory"));

                // 3. 命令加权平均延迟（commandstats 差值）
                long[] cmdSum = parseCommandStats(cmdStats);
                long totalCalls = cmdSum[0];
                long totalUsec = cmdSum[1];
                if (hasLastSample && (totalCalls - lastCmdCalls) > 0) {
                    long deltaCalls = totalCalls - lastCmdCalls;
                    long deltaUsec = totalUsec - lastCmdUsec;
                    double latency = (double) deltaUsec / deltaCalls;
                    data.setCmdLatency(String.format("%.2f", latency));
                } else {
                    data.setCmdLatency("0.00");
                }
                lastCmdCalls = totalCalls;
                lastCmdUsec = totalUsec;

                // 4. 网络带宽
                data.setIoIik(stats.getOrDefault("instantaneous_input_kbps", "0"));
                data.setIoIok(stats.getOrDefault("instantaneous_output_kbps", "0"));

                // 5. QPS
                data.setOps(stats.getOrDefault("instantaneous_ops_per_sec", "0"));

                // 6. 节点身份
                data.setRole(server.getOrDefault("redis_mode", "standalone"));
                String role = repl.get("role");
                if (role != null) data.setRole(role);
                data.setRedisVersion(server.get("redis_version"));
                data.setRunId(server.get("run_id"));

                // 7. 主备情况
                data.setMasterHost(repl.get("master_host"));
                String masterPort = repl.get("master_port");
                if (masterPort != null) data.setMasterPort(masterPort);
                data.setConnectedSlaves(repl.get("connected_slaves"));

                // 8. 主从复制延迟
                String replOffset = repl.get("master_last_io_seconds_ago");
                if (replOffset == null) replOffset = "0";
                data.setReplDelay(replOffset);

                data.setStatus("up");
                hasLastSample = true;
            }
        } catch (Exception e) {
            log.error("采集 Redis 指标失败", e);
            data.setStatus("down");
            data.setErrorMsg(e.getMessage());
        }
        return data;
    }

    /** 解析 INFO 返回为 key-value */
    private Map<String, String> parseInfo(String info) {
        Map<String, String> map = new HashMap<>();
        if (info == null) return map;
        for (String line : info.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int idx = line.indexOf(':');
            if (idx > 0) {
                map.put(line.substring(0, idx), line.substring(idx + 1).trim());
            }
        }
        return map;
    }

    /** 解析 commandstats，返回 [总calls, 总usec] */
    private long[] parseCommandStats(String cmdStats) {
        long totalCalls = 0;
        long totalUsec = 0;
        if (cmdStats == null) return new long[]{0, 0};
        for (String line : cmdStats.split("\n")) {
            line = line.trim();
            if (!line.startsWith("cmdstat_")) continue;
            // cmdstat_get:calls=1000,usec=5000,usec_per_call=5.00
            String[] parts = line.split(":");
            if (parts.length < 2) continue;
            String[] kv = parts[1].split(",");
            long calls = 0, usec = 0;
            for (String pair : kv) {
                if (pair.startsWith("calls=")) {
                    calls = parseLong(pair.substring(6));
                } else if (pair.startsWith("usec=")) {
                    usec = parseLong(pair.substring(5));
                }
            }
            totalCalls += calls;
            totalUsec += usec;
        }
        return new long[]{totalCalls, totalUsec};
    }

    private long parseMemHuman(String s) {
        if (s == null) return 0;
        s = s.trim().toUpperCase();
        try {
            if (s.endsWith("GB")) return (long) (Double.parseDouble(s.replace("GB","")) * 1024L * 1024 * 1024);
            if (s.endsWith("MB")) return (long) (Double.parseDouble(s.replace("MB","")) * 1024L * 1024);
            if (s.endsWith("KB")) return (long) (Double.parseDouble(s.replace("KB","")) * 1024L);
            if (s.endsWith("B")) return Long.parseLong(s.replace("B",""));
            return Long.parseLong(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private double getDouble(String s) {
        if (s == null || s.isEmpty()) return 0;
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0; }
    }

    private long getLong(String s) {
        if (s == null || s.isEmpty()) return 0;
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; }
    }

    private long parseLong(String s) {
        if (s == null || s.isEmpty()) return 0;
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; }
    }

    public void destroy() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
        }
    }
}



