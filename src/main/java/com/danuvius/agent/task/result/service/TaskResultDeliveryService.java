package com.danuvius.agent.task.result.service;

import com.danuvius.agent.client.PlatformClient;
import com.danuvius.agent.common.operation.OperationType;
import com.danuvius.agent.config.AgentProperties;
import com.danuvius.agent.registration.service.RegisterService;
import com.danuvius.agent.task.result.model.TaskResultRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 负责“先落盘、后上报、失败后补报”的任务结果交付流程。
 */
@Slf4j
@Service
public class TaskResultDeliveryService {

    private final TaskResultStore resultStore;
    private final PlatformClient platformClient;
    private final AgentProperties properties;
    private final RegisterService registerService;

    /** 注入结果存储、平台注册信息和 HTTP 客户端。 */
    public TaskResultDeliveryService(TaskResultStore resultStore,
                                     PlatformClient platformClient,
                                     AgentProperties properties,
                                     RegisterService registerService) {
        this.resultStore = resultStore;
        this.platformClient = platformClient;
        this.properties = properties;
        this.registerService = registerService;
    }

    /**
     * 保存刚执行完成的最终结果，并立即尝试上报一次。
     *
     * @param taskNo 业务任务编号
     * @param stepNo 步骤序号
     * @param operationType 原子操作类型
     * @param status SUCCESS 或 FAILED
     * @param resultJson 完整 OperationResult JSON
     */
    public void recordAndReport(String taskNo, int stepNo, OperationType operationType,
                                String status, String resultJson) {
        TaskResultRecord record = new TaskResultRecord();
        record.setTaskNo(taskNo);
        record.setStepNo(stepNo);
        record.setAgentId(registerService.getAgentId());
        record.setOperationType(operationType.name());
        record.setStatus(status);
        record.setResult(resultJson);
        record.setFinishTime(System.currentTimeMillis());
        record.setReported(false);

        // 必须先持久化，再发网络请求；这样网络断开或进程重启都不会丢失结果。
        resultStore.save(record);
        tryReport(record);
    }

    /** 查询指定步骤已落盘的最终结果，供平台主动核对。 */
    public TaskResultRecord findResult(String taskNo, int stepNo) {
        return resultStore.find(taskNo, stepNo);
    }

    /** 重新上报所有尚未获得平台确认的结果。 */
    public void retryUnreportedResults() {
        List<TaskResultRecord> records = resultStore.findUnreported();
        for (TaskResultRecord record : records) {
            tryReport(record);
        }
    }

    /** 对一个已存在的结果立即执行补报。 */
    public void reportStoredResult(TaskResultRecord record) {
        if (record != null) {
            // 重复下发说明平台仍需要结果，即使历史上确认过也再次执行幂等上报。
            tryReport(record);
        }
    }

    /** 调用平台回调接口；只有收到2xx响应后才标记为已确认。 */
    private void tryReport(TaskResultRecord record) {
        try {
            platformClient.reportTaskResult(
                    record.getTaskNo(),
                    record.getStepNo(),
                    record.getAgentId(),
                    OperationType.fromValue(record.getOperationType()),
                    record.getStatus(),
                    record.getResult(),
                    properties.getPlatformUrl(),
                    registerService.getToken()
            );
            resultStore.markReported(record);
            log.info("运维任务结果已获得平台确认: taskNo={}, stepNo={}, status={}",
                    record.getTaskNo(), record.getStepNo(), record.getStatus());
        } catch (Exception e) {
            // 文件仍保留 reported=false，定时任务会继续补报。
            log.warn("运维任务结果暂未送达平台，将自动补报: taskNo={}, stepNo={}, status={}",
                    record.getTaskNo(), record.getStepNo(), record.getStatus(), e);
        }
    }
}
