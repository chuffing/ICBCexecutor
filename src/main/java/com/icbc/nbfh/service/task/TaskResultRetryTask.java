package com.icbc.nbfh.service.task;

import com.icbc.nbfh.service.TaskResultDeliveryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时补报尚未得到平台确认的运维步骤结果。
 */
@Slf4j
@Component
public class TaskResultRetryTask {

    private final TaskResultDeliveryService deliveryService;

    /** 注入可靠结果交付服务。 */
    public TaskResultRetryTask(TaskResultDeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    /** 按配置间隔扫描本地未确认结果并重新上报。 */
    @Scheduled(fixedDelayString = "${agent.task-result-retry-interval:5000}")
    public void retry() {
        try {
            deliveryService.retryUnreportedResults();
        } catch (Exception e) {
            log.error("扫描或补报本地任务结果失败", e);
        }
    }
}
