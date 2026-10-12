package com.icbc.nbfh.service;

import com.icbc.nbfh.service.client.PlatformClient;
import com.icbc.nbfh.common.constant.OperationType;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.TaskResultRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 将运维结果持久化到执行器本地，并尝试上报主平台。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskResultDeliveryService {

    private final TaskResultStore resultStore;
    private final PlatformClient platformClient;
    private final AgentProperties properties;
    private final RegisterService registerService;

    /**
     * 保存刚执行完成的最终结果，并立即尝试上报一次。
     *
     * @param taskNo 当前运维任务编号
     * @param stepNo 当前步骤组编号
     * @param operationType 本次执行的擦欧总
     * @param status SUCCESS 或 FAILED
     * @param resultJson 详细执行结果的JSON字符串
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

        // 先持久化。
        resultStore.save(record);

        // 尝试向平台上报结果。
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
