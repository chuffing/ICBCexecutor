package com.danuvius.agent.heartbeat.task;

import com.danuvius.agent.heartbeat.service.HeartbeatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 0.5分钟（30秒）定时心跳任务
 */
@Slf4j
@Component
public class HeartbeatTask {

    @Autowired
    private HeartbeatService heartbeatService;

    /**
     * 固定30秒心跳一次
     */
    @Scheduled(fixedDelayString = "${agent.heartbeat-interval:30000}")
    public void heartbeat() {
        heartbeatService.sendHeartbeat();
    }
}
