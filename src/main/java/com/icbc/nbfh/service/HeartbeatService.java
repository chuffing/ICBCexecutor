package com.icbc.nbfh.service;

/**
 * 心跳服务
 */
public interface HeartbeatService {

    /**
     * 执行一次心跳。
     */
    void sendHeartbeat();
}
