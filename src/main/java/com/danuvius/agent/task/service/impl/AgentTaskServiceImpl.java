package com.danuvius.agent.task.service.impl;

import com.alibaba.fastjson.JSON;
import com.danuvius.agent.client.PlatformClient;
import com.danuvius.agent.config.AgentProperties;
import com.danuvius.agent.host.operation.PrecheckHostOperation;
import com.danuvius.agent.metric.service.CollectService;
import com.danuvius.agent.task.model.PlatformTask;
import com.danuvius.agent.common.operation.OperationErrorCode;
import com.danuvius.agent.common.operation.OperationResult;
import com.danuvius.agent.common.operation.OperationType;
import com.danuvius.agent.recovery.operation.RestoreConfigOperation;
import com.danuvius.agent.configdeploy.operation.DeployConfigOperation;
import com.danuvius.agent.redis.operation.StartRedisOperation;
import com.danuvius.agent.version.operation.DeployVersionOperation;
import com.danuvius.agent.version.operation.RollbackVersionOperation;
import com.danuvius.agent.version.operation.SwitchVersionOperation;
import com.danuvius.agent.redis.operation.StopRedisOperation;
import com.danuvius.agent.sentinel.operation.StartSentinelOperation;
import com.danuvius.agent.sentinel.operation.StopSentinelOperation;
import com.danuvius.agent.task.service.AgentTaskService;
import com.danuvius.agent.registration.service.RegisterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Agent 运维任务执行器。
 *
 * 所有任务必须先通过 OperationType 白名单校验，再进入具体操作处理器。
 */
@Slf4j //lombok自动生成日志对象
@Service
public class AgentTaskServiceImpl implements AgentTaskService {

    @Autowired
    private PlatformClient platformClient;

    @Autowired
    private AgentProperties properties;

    @Autowired
    private RegisterService registerService;

    @Autowired
    private CollectService collectService;

    @Autowired
    private PrecheckHostOperation precheckHostOperation;

    @Autowired
    private DeployVersionOperation deployVersionOperation;

    @Autowired
    private SwitchVersionOperation switchVersionOperation;

    @Autowired
    private RollbackVersionOperation rollbackVersionOperation;

    @Autowired
    private RestoreConfigOperation restoreConfigOperation;

    @Autowired
    private DeployConfigOperation deployConfigOperation;

    @Autowired
    private StartRedisOperation startRedisOperation;

    @Autowired
    private StopRedisOperation stopRedisOperation;

    @Autowired
    private StartSentinelOperation startSentinelOperation;

    @Autowired
    private StopSentinelOperation stopSentinelOperation;

    @Autowired
    @Qualifier("operationTaskExecutor")
    private ThreadPoolTaskExecutor operationTaskExecutor;

    /**
     * 校验任务基础字段，并将任务提交到本机单线程运维执行器。
     *
     * @param task 平台下发的本机操作任务
     */
    @Override
    public void submitTask(PlatformTask task) {
        if (task == null) {
            throw new IllegalArgumentException("任务内容不能为空");
        }

        String taskNo = task.requireTaskNo();
        int stepNo = task.requireStepNo();
        OperationType operationType = task.resolveOperationType();
        Map<String, String> params = task.getParams() == null
                ? Collections.emptyMap()
                : new HashMap<>(task.getParams());
        operationTaskExecutor.execute(() -> executeTask(taskNo, stepNo, operationType, params));
        log.info("任务已提交到运维线程: taskNo={}, stepNo={}, type={}",
                taskNo, stepNo, operationType);
    }

    /**
     * 执行一个已经通过基础校验的任务，并向平台上报最终结果。
     *
     * @param taskNo 业务任务编号
     * @param stepNo 当前操作在业务任务中的步骤序号
     * @param operationType 固定操作类型
     * @param params 当前操作参数的只读副本
     */
    private void executeTask(String taskNo, int stepNo, OperationType operationType,
                             Map<String, String> params) {
        log.info("开始执行运维任务: taskNo={}, stepNo={}, type={}",
                taskNo, stepNo, operationType);

        OperationResult result;
        try {
            result = executeOperation(taskNo, operationType, params);
        } catch (LinkageError e) {
            log.error("运维任务发生依赖链接错误: taskNo={}, type={}", taskNo, operationType, e);
            long now = System.currentTimeMillis();
            result = OperationResult.failure(
                    OperationErrorCode.UNKNOWN_ERROR,
                    "运维任务运行依赖不完整",
                    now,
                    now
            );
        } catch (Exception e) {
            log.error("运维任务发生未处理异常: taskNo={}, type={}", taskNo, operationType, e);
            long now = System.currentTimeMillis();
            result = OperationResult.failure(
                    OperationErrorCode.UNKNOWN_ERROR,
                    "运维任务发生未知错误",
                    now,
                    now
            );
        }

        String status = result.isSuccess() ? "SUCCESS" : "FAILED";
        log.info(
                "运维任务执行完成: taskNo={}, type={}, status={}, errorCode={}, message={}",
                taskNo,
                operationType,
                status,
                result.getErrorCode(),
                result.getMessage()
        );

        try {
            platformClient.reportTaskResult(
                    taskNo,
                    stepNo,
                    registerService.getAgentId(),
                    operationType,
                    status,
                    JSON.toJSONString(result),
                    properties.getPlatformUrl(),
                    registerService.getToken()
            );
            log.info(
                    "运维任务结果已上报: taskNo={}, stepNo={}, agentId={}, operationType={}, status={}",
                    taskNo,
                    stepNo,
                    registerService.getAgentId(),
                    operationType,
                    status
            );
        } catch (Exception e) {
            log.error(
                    "运维任务结果上报失败: taskNo={}, operationType={}, status={}",
                    taskNo,
                    operationType,
                    status,
                    e
            );
        }

        refreshRuntimeStatusAfterRecovery(result, operationType);
    }

    /**
     * 最终启动Sentinel成功后立即刷新运行状态。
     *
     * <p>故障恢复、版本升级和服务器切换都以启动Sentinel作为节点最后一步。
     * 立即采集可以覆盖停止阶段或服务刚启动时产生的DOWN状态。</p>
     */
    private void refreshRuntimeStatusAfterRecovery(OperationResult result,
                                                   OperationType operationType) {
        if (result.isSuccess() && operationType == OperationType.START_SENTINEL) {
            collectService.collectAndReport();
            log.info("Redis与Sentinel最终状态已重新采集并上报");
        }
    }

    /**
     * 将白名单操作类型分发到对应的本机处理器。
     *
     * @param taskNo 业务任务编号
     * @param operationType 固定操作类型
     * @param params 当前操作需要的参数
     * @return 对应处理器的统一操作结果
     */
    private OperationResult executeOperation(String taskNo, OperationType operationType,
                                             Map<String, String> params) {
        if (operationType == OperationType.PRECHECK_HOST) {
            return precheckHostOperation.execute();
        }
        if (operationType == OperationType.DEPLOY_VERSION) {
            return deployVersionOperation.execute(taskNo, params);
        }
        if (operationType == OperationType.SWITCH_VERSION) {
            return switchVersionOperation.execute(taskNo, params);
        }
        if (operationType == OperationType.ROLLBACK_VERSION) {
            return rollbackVersionOperation.execute();
        }
        if (operationType == OperationType.RESTORE_CONFIG) {
            return restoreConfigOperation.execute(params);
        }
        if (operationType == OperationType.DEPLOY_CONFIG) {
            return deployConfigOperation.execute(taskNo, params);
        }
        if (operationType == OperationType.START_REDIS) {
            return startRedisOperation.execute(params);
        }
        if (operationType == OperationType.STOP_REDIS) {
            return stopRedisOperation.execute();
        }
        if (operationType == OperationType.START_SENTINEL) {
            return startSentinelOperation.execute(params);
        }
        if (operationType == OperationType.STOP_SENTINEL) {
            return stopSentinelOperation.execute();
        }

        long now = System.currentTimeMillis();
        return OperationResult.failure(
                OperationErrorCode.OPERATION_NOT_IMPLEMENTED,
                "任务类型尚未实现: " + operationType,
                now,
                now
        );
    }
}
