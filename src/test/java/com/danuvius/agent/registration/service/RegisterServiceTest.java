package com.danuvius.agent.registration.service;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

public class RegisterServiceTest {
    // 在 RedisInfoCollector 中加一个 main 方法
    public static void main(String[] args) {
        JedisPoolConfig config = new JedisPoolConfig();
        JedisPool pool = new JedisPool(
                config,
                System.getenv("TEST_REDIS_HOST"), // 通过环境变量指定测试Redis地址
                6379,            // 端口
                5000,            // 连接超时（毫秒），默认 2000 容易超时
                System.getenv("TEST_REDIS_PASSWORD") // 通过环境变量提供测试密码
        );
        try (Jedis jedis = pool.getResource()) {
            System.out.println("INFO server:");
            System.out.println(jedis.info("server"));
            System.out.println("---");
            System.out.println("INFO stats:");
            System.out.println(jedis.info("stats"));
            System.out.println("---");
            System.out.println("INFO replication:");
            System.out.println(jedis.info("replication"));
            System.out.println("---");
            System.out.println("INFO commandstats:");
            System.out.println(jedis.info("commandstats"));
        }
        pool.close();
    }

}
