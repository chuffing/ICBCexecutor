package com.icbc.nbfh.service.impl;

import com.icbc.nbfh.service.client.PlatformClient;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.dto.MetricData;
import com.icbc.nbfh.service.CollectService;
import com.icbc.nbfh.service.collector.RedisInfoCollector;
import com.icbc.nbfh.service.RegisterService;
import com.icbc.nbfh.service.health.SentinelHealthChecker;
import com.icbc.nbfh.entity.result.SentinelHealthResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 周期采集本机Redis和Sentinel状态，并统一上报给平台。 */
@Slf4j
@Service
public class CollectServiceImpl implements CollectService {

    @Autowired private RedisInfoCollector collector;
    @Autowired private SentinelHealthChecker sentinelHealthChecker;
    @Autowired private PlatformClient platformClient;
    @Autowired private RegisterService registerService;
    @Autowired private AgentProperties properties;

    /** 完成一次Redis采集、Sentinel检查和平台上报。 */
    @Override
    public void collectAndReport() {
        if (registerService.getToken() == null) {
            log.warn("未注册，跳过采集");
            return;
        }
        try {
            MetricData metric = collector.collectWithReconnect();
            // Sentinel检查失败不阻止Redis结果上报，两类状态分别保存和展示。
            appendSentinelHealth(metric, sentinelHealthChecker.check());
            platformClient.reportMetric(metric, properties.getPlatformUrl(),
                    registerService.getToken(), registerService.getAgentId());
            log.debug("采集并上报完成, redisStatus={}, sentinelStatus={}",
                    metric.getStatus(), metric.getSentinelStatus());
        } catch (Exception e) {
            log.error("采集上报失败", e);
        }
    }

    /** 把一次Sentinel健康检查结果合并到同一份周期状态中。 */
    private void appendSentinelHealth(MetricData metric, SentinelHealthResult result) {
        metric.setSentinelStatus(result.isHealthy() ? "up" : "down");
        metric.setSentinelMasterName(result.getMasterName());
        metric.setSentinelMasterHost(result.getMasterHost());
        metric.setSentinelMasterPort(result.getMasterPort());
        metric.setSentinelErrorMsg(result.isHealthy() ? null : result.getMessage());
    }
}