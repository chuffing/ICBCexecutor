package com.danuvius.agent.heartbeat.service;

/**
 * 心跳服务
 */
public interface HeartbeatService {

    /**
     * 执行一次心跳。
     */
    void sendHeartbeat();
}
