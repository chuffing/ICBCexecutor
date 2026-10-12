package com.icbc.nbfh.service.client;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.icbc.nbfh.common.constant.OperationType;
import com.icbc.nbfh.entity.dto.HeartbeatRequest;
import com.icbc.nbfh.entity.dto.MetricData;
import com.icbc.nbfh.entity.dto.RegisterRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * 与平台通信的 HTTP 客户端
 */
@Slf4j
@Component
public class PlatformClient {

    @Autowired
    private RestTemplate restTemplate;

    /**
     * 注册 Agent
     */
    public Map<String, Object> register(RegisterRequest request, String platformUrl) {
        String url = platformUrl + "/api/agent/register";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(JSON.toJSONString(request), headers);
        ResponseEntity<String> resp = restTemplate.postForEntity(url, entity, String.class);
        return JSON.parseObject(resp.getBody(), new TypeReference<Map<String, Object>>() {});
    }

    /**
     * 查询平台是否已经保存当前执行器的基准配置快照。
     */
    public Map<String, Object> getConfigSnapshotStatus(
            String agentId, String platformUrl, String token) {
        String url = platformUrl + "/api/agent/config-snapshot?agentId=" + agentId;
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.GET, entity, String.class);
        return JSON.parseObject(response.getBody(),
                new TypeReference<Map<String, Object>>() {});
    }

    /**
     * 上传本机两份备份配置；平台首次保存，之后只进行一致性校验。
     */
    public Map<String, Object> uploadConfigSnapshot(
            String agentId,
            String redisConfigBase64,
            String sentinelConfigBase64,
            String platformUrl,
            String token) {
        String url = platformUrl + "/api/agent/config-snapshot";
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("redisConfigBase64", redisConfigBase64);
        body.put("sentinelConfigBase64", sentinelConfigBase64);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + token);
        HttpEntity<String> entity = new HttpEntity<>(JSON.toJSONString(body), headers);
        ResponseEntity<String> response = restTemplate.postForEntity(
                url, entity, String.class);
        return JSON.parseObject(response.getBody(),
                new TypeReference<Map<String, Object>>() {});
    }

    /**
     * 发送心跳
     */
    public void heartbeat(HeartbeatRequest request, String platformUrl, String token) {
        String url = platformUrl + "/api/agent/heartbeat";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + token);
        HttpEntity<String> entity = new HttpEntity<>(JSON.toJSONString(request), headers);
        restTemplate.postForEntity(url, entity, String.class);
    }

    /**
     * 上报采集数据
     */
    public void reportMetric(MetricData metric, String platformUrl, String token, String agentId) {
        String url = platformUrl + "/api/metric/report";
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("metric", metric);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + token);
        HttpEntity<String> entity = new HttpEntity<>(JSON.toJSONString(body), headers);
        restTemplate.postForEntity(url, entity, String.class);
    }

    /**
     * 上报本机某个运维步骤的执行结果。
     * taskNo 标识平台上的业务任务，agentId 标识执行节点，operationType
     * 标识本次完成的具体步骤。平台使用这三个信息判断哪个节点完成了当前步骤。
     *
     * @param taskNo 平台业务任务编号
     * @param stepNo 当前操作在业务任务中的步骤序号
     * @param agentId 当前执行器编号
     * @param operationType 本次执行的具体运维操作
     * @param status 执行状态，取值为 SUCCESS 或 FAILED
     * @param result OperationResult 序列化后的详细结果
     * @param platformUrl 平台基础地址
     * @param token 当前执行器注册后获得的认证令牌
     */
    public void reportTaskResult(String taskNo, int stepNo, String agentId,
                                 OperationType operationType, String status,
                                 String result, String platformUrl, String token) {
        String url = platformUrl + "/api/task/report";
        Map<String, Object> body = new HashMap<>();
        body.put("taskNo", taskNo);
        body.put("stepNo", stepNo);
        body.put("agentId", agentId);
        // 上报实际执行的操作类型，平台据此匹配当前任务步骤并过滤迟到回调。
        body.put("operationType", operationType.name());
        body.put("status", status);
        body.put("result", result);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + token);
        HttpEntity<String> entity = new HttpEntity<>(JSON.toJSONString(body), headers);
        restTemplate.postForEntity(url, entity, String.class);
    }
}
