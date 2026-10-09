package com.danuvius.agent.metric.service;

/**
 * 采集服务
 */
public interface CollectService {

    /**
     * 执行一次采集并上报
     */
    void collectAndReport();
}
