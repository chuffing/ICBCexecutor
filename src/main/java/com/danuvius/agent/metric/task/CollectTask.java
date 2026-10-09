package com.danuvius.agent.metric.task;

import com.danuvius.agent.metric.service.CollectService;
import com.danuvius.agent.registration.service.RegisterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/** 负责启动注册以及周期状态采集。 */
@Slf4j
@Component
public class CollectTask {

    @Autowired
    private CollectService collectService;

    @Autowired
    private RegisterService registerService;

    /**
     * 应用启动两秒后完成注册，并立即上报一次状态。
     *
     * <p>立即采集可以避免Agent重启后等待完整的五分钟周期才在平台显示状态。</p>
     */
    @PostConstruct
    public void init() {
        Thread startupThread = new Thread(() -> {
            try {
                Thread.sleep(2000);
                registerService.registerIfNeeded();
                collectService.collectAndReport();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // 启动采集失败时保留进程，后续定时任务仍会继续重试。
                log.error("启动注册或首次状态上报失败", e);
            }
        }, "agent-startup-register");
        startupThread.start();
    }

    /** 按配置的间隔采集并上报Redis和Sentinel状态。 */
    @Scheduled(fixedDelayString = "${agent.collect-interval:300000}")
    public void collect() {
        collectService.collectAndReport();
    }
}
