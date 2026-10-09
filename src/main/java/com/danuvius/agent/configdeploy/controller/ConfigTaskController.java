package com.danuvius.agent.configdeploy.controller;

import com.danuvius.agent.configdeploy.exception.ConfigPackageConflictException;
import com.danuvius.agent.configdeploy.service.ConfigPackageService;
import com.danuvius.agent.registration.service.RegisterService;
import com.danuvius.agent.task.model.PlatformTask;
import com.danuvius.agent.task.service.AgentTaskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 接收服务器切换时平台审核过的Redis和Sentinel配置。
 */
@RestController
@RequestMapping("/api/agent")
public class ConfigTaskController {

    @Autowired
    private ConfigPackageService configPackageService;

    @Autowired
    private AgentTaskService agentTaskService;

    @Autowired
    private RegisterService registerService;

    /**
     * 接收两份配置并在校验成功后提交DEPLOY_CONFIG操作。
     *
     * @param authorization 平台Bearer token
     * @param task 平台创建的DEPLOY_CONFIG任务
     * @param redisFile Redis配置文件
     * @param sentinelFile Sentinel配置文件
     * @return 接收状态；最终执行结果由Agent异步上报
     */
    @PostMapping(value = "/config-task", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> receiveConfigTask(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestPart("task") PlatformTask task,
            @RequestPart("redisFile") MultipartFile redisFile,
            @RequestPart("sentinelFile") MultipartFile sentinelFile) {
        if (!isAuthorized(authorization)) {
            return response(HttpStatus.UNAUTHORIZED, null, "UNAUTHORIZED", "平台令牌无效");
        }

        String taskNo = task == null ? null : task.getTaskNo();
        try {
            configPackageService.accept(task, redisFile, sentinelFile);
            agentTaskService.submitTask(task);
            return response(
                    HttpStatus.ACCEPTED,
                    taskNo,
                    "ACCEPTED",
                    "配置文件已接收并校验，部署任务已提交"
            );
        } catch (ConfigPackageConflictException e) {
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
                    "配置文件保存失败"
            );
        }
    }

    /**
     * 校验请求Bearer token。
     *
     * @param authorization Authorization请求头
     * @return 与本机token完全一致时返回true
     */
    private boolean isAuthorized(String authorization) {
        String token = registerService.getToken();
        return token != null && ("Bearer " + token).equals(authorization);
    }

    /**
     * 构造统一HTTP响应。
     *
     * @param httpStatus HTTP状态码
     * @param taskNo 业务任务编号
     * @param status 接口状态
     * @param message 结果说明
     * @return HTTP响应
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
