package com.icbc.nbfh.controller;

import com.icbc.nbfh.entity.TaskResultRecord;
import com.icbc.nbfh.service.TaskResultDeliveryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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

    /** 注入结果查询服务。 */
    public TaskResultQueryController(TaskResultDeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    /**
     * 按任务编号和步骤序号返回本机已经完成的最终结果。
     *
     * @param taskNo 业务任务编号
     * @param stepNo 步骤序号
     * @return 已保存的结果；未完成或未找到时返回404
     */
    @GetMapping
    public ResponseEntity<TaskResultRecord> query(
            @RequestParam("taskNo") String taskNo,
            @RequestParam("stepNo") int stepNo) {
        // 请求到达这里时，拦截器已经完成平台令牌校验。
        TaskResultRecord record = deliveryService.findResult(taskNo, stepNo);
        if (record == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(record);
    }

}
