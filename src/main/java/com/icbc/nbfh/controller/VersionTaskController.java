package com.icbc.nbfh.controller;

import com.icbc.nbfh.entity.dto.PlatformTask;
import com.icbc.nbfh.service.AgentTaskService;
import com.icbc.nbfh.common.exception.VersionPackageConflictException;
import com.icbc.nbfh.service.VersionPackageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 接收平台创建好的版本部署任务及对应版本包。
 */
@RestController
@RequestMapping("/api/agent")
public class VersionTaskController {

    @Autowired
    private VersionPackageService versionPackageService;

    @Autowired
    private AgentTaskService agentTaskService;

    /**
     * 接收平台发送的版本部署任务和对应的 tar.gz 版本包。
     *
     * <p>接口先验证平台令牌，再保存并校验版本包，最后把部署任务提交到运维线程。
     * 返回 202 只表示任务已经受理，最终执行结果仍由 Agent 异步上报给平台。</p>
     *
     * @param task 平台创建的 DEPLOY_VERSION 任务
     * @param file 与任务对应的 Redis 版本包
     * @return 本次接收是否成功；不是版本部署的最终执行结果
     */
    @PostMapping(value = "/version-task", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> receiveVersionTask(
            @RequestPart("task") PlatformTask task,
            @RequestPart("file") MultipartFile file) {
        // 请求到达这里时，拦截器已经完成平台令牌校验。
        String taskNo = task == null ? null : task.getTaskNo();
        try {
            // 接收和校验版本包
            versionPackageService.accept(task, file);
            // 版本包确认保存成功之后，再提交异步运维任务
            agentTaskService.submitTask(task);
            return response(
                    HttpStatus.ACCEPTED,
                    taskNo,
                    "ACCEPTED",
                    "版本包已接收并校验，部署任务已提交"
            );
        } catch (VersionPackageConflictException e) {
            return response(HttpStatus.CONFLICT, taskNo, "CONFLICT", e.getMessage());
        } catch (TaskRejectedException e) {
            return response(HttpStatus.CONFLICT, taskNo, "BUSY", "执行器当前有任务正在运行");
        } catch (IllegalArgumentException e) {
            return response(HttpStatus.BAD_REQUEST, taskNo, "REJECTED", e.getMessage());
        } catch (IOException | IllegalStateException e) {
            return response(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    taskNo,
                    "FAILED",
                    "版本包保存失败"
            );
        }
    }

    /**
     * 按统一结构构造版本包接口的 HTTP 响应。
     *
     * @param httpStatus HTTP 状态码
     * @param taskNo 业务任务编号
     * @param status 接口处理状态
     * @param message 可供平台展示的结果说明
     * @return 包含 taskNo、status 和 message 的响应
     */
    private ResponseEntity<Map<String, Object>> response(
            HttpStatus httpStatus, String taskNo, String status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskNo", taskNo);
        body.put("status", status);
        body.put("message", message);
        return ResponseEntity.status(httpStatus).body(body);
    }
}
