package com.danuvius.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Redis Monitor Agent - 部署在生产服务器
 * 职责：采集本地 Redis 指标、心跳上报、接收并执行运维任务
 */
@EnableScheduling
@SpringBootApplication
public class RedisMonitorAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(RedisMonitorAgentApplication.class, args);
    }
}
