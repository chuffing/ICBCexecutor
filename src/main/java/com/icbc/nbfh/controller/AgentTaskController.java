package com.icbc.nbfh.controller;

import com.icbc.nbfh.entity.dto.PlatformTask;
import com.icbc.nbfh.service.AgentTaskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 接收平台主动下发的运维任务。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentTaskController {

    @Autowired
    private AgentTaskService agentTaskService;

    /** 接收并认证主平台下发的运维任务，将合法任务提交至异步执行线程。 */
    @PostMapping("/task")
    public ResponseEntity<Map<String, Object>> receiveTask(@RequestBody PlatformTask task) {

        // 请求到达这里时，拦截器已经完成平台令牌校验。
        try {
            // 校验运维任务并提交线程池异步执行，避免阻塞 HTTP 请求线程。
            agentTaskService.submitTask(task);
            return response(HttpStatus.ACCEPTED,
                    task.getTaskNo(),
                    "ACCEPTED",
                    "任务已接收");
        } catch (IllegalArgumentException e) {
            String taskNo = task == null ? null : task.getTaskNo();
            return response(HttpStatus.BAD_REQUEST,
                    taskNo,
                    "REJECTED",
                    e.getMessage());
        } catch (TaskRejectedException e) {
            String taskNo = task == null ? null : task.getTaskNo();
            return response(HttpStatus.CONFLICT,
                    taskNo,
                    "BUSY",
                    "执行器当前有任务正在运行");
        }
    }

    private ResponseEntity<Map<String, Object>> response(
            HttpStatus httpStatus, String taskNo, String status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskNo", taskNo);
        body.put("status", status);
        body.put("message", message);
        return ResponseEntity.status(httpStatus).body(body);
        // ResponseEntity = http状态码 + 响应body
    }
}
