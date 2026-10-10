package com.danuvius.agent.task.result.controller;

import com.danuvius.agent.registration.service.RegisterService;
import com.danuvius.agent.task.result.model.TaskResultRecord;
import com.danuvius.agent.task.result.service.TaskResultDeliveryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 供平台在回调丢失或响应超时时主动查询执行结果。
 */
@RestController
@RequestMapping("/api/agent/task-result")
public class TaskResultQueryController {

    private final TaskResultDeliveryService deliveryService;
    private final RegisterService registerService;

    /** 注入结果查询服务和执行器认证信息。 */
    public TaskResultQueryController(TaskResultDeliveryService deliveryService,
                                     RegisterService registerService) {
        this.deliveryService = deliveryService;
        this.registerService = registerService;
    }

    /**
     * 按任务编号和步骤序号返回本机已经完成的最终结果。
     *
     * @param authorization 平台下发任务时使用的 Bearer Token
     * @param taskNo 业务任务编号
     * @param stepNo 步骤序号
     * @return 已保存的结果；未完成或未找到时返回404
     */
    @GetMapping
    public ResponseEntity<TaskResultRecord> query(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam("taskNo") String taskNo,
            @RequestParam("stepNo") int stepNo) {
        if (!isAuthorized(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        TaskResultRecord record = deliveryService.findResult(taskNo, stepNo);
        if (record == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(record);
    }

    /** 使用执行器注册后保存的令牌验证平台身份。 */
    private boolean isAuthorized(String authorization) {
        String token = registerService.getToken();
        return token != null && ("Bearer " + token).equals(authorization);
    }
}
