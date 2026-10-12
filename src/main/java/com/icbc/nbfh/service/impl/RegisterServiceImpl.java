package com.icbc.nbfh.service.impl;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.net.NetUtil;
import cn.hutool.system.HostInfo;
import cn.hutool.system.OsInfo;
import com.icbc.nbfh.service.client.PlatformClient;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.dto.RegisterRequest;
import com.icbc.nbfh.service.ConfigSnapshotService;
import com.icbc.nbfh.service.RegisterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class RegisterServiceImpl implements RegisterService {

    @Autowired
    private AgentProperties properties;

    @Autowired
    private PlatformClient platformClient;

    @Autowired
    private ConfigSnapshotService configSnapshotService;

    /** Agent 对外提供运维任务接口的端口，注册时需要上报给平台。 */
    @Value("${server.port:9101}")
    private int agentPort;

    private String token;
    private String agentId;

    @Override
    public boolean registerIfNeeded() {
        // 先尝试从本地文件读取 token
        File tokenFile = new File(properties.getTokenFile());
        if (tokenFile.exists()) {
            String content = FileUtil.readString(tokenFile, StandardCharsets.UTF_8);
            String[] parts = content.split("\\|");
            if (parts.length == 2) {
                this.agentId = parts[0];
                this.token = parts[1];
                log.info("从本地文件读取到 agentId={}", agentId);
                initializeOrVerifyConfigSnapshot();
                return true;
            }
        }

        // 本地无 token，向平台注册
        log.info("开始向平台注册 agent...");
/*        try {
            RegisterRequest req = new RegisterRequest();
            req.setRegisterCode(properties.getRegisterCode());
            HostInfo hostInfo = new HostInfo();
            String hostname = hostInfo.getName();
            req.setAgentName(properties.getAgentName() != null && !properties.getAgentName().isEmpty()
                    ? properties.getAgentName() : hostname);
            req.setHostname(hostname);
            req.setIp(NetUtil.getLocalhostStr());
            req.setOs(new OsInfo().getName());

            Map<String, String> redisInfo = new HashMap<>();
            redisInfo.put("host", properties.getRedis().getHost());
            redisInfo.put("port", String.valueOf(properties.getRedis().getPort()));
            req.setRedisInfo(redisInfo);

            Map<String, Object> result = platformClient.register(req, properties.getPlatformUrl());
            Object data = result.get("data");
            if (data instanceof Map) {
                Map<?, ?> dataMap = (Map<?, ?>) data;
                this.token = (String) dataMap.get("token");
                this.agentId = (String) dataMap.get("agentId");
                if (token != null && agentId != null) {
                    // 持久化 token
                    FileUtil.writeString(agentId + "|" + token, tokenFile, StandardCharsets.UTF_8);
                    log.info("注册成功，agentId={}", agentId);
                    return true;
                }
            }
            log.error("注册失败，平台返回: {}", result);
        } catch (Exception e) {
            log.error("注册请求异常", e);
        }
        return false;*/

        try {
            RegisterRequest req = new RegisterRequest();
            // 校验注册码配置
            String registerCode = properties.getRegisterCode();
            if (registerCode == null || registerCode.trim().isEmpty()) {
                log.error("注册失败：registerCode 配置为空，请检查配置文件");
                return false;
            }
            req.setRegisterCode(registerCode);

            // 获取主机名
            HostInfo hostInfo = new HostInfo();
            String hostname = hostInfo.getName();

            String agentName = properties.getAgentName();
            req.setAgentName(agentName != null && !agentName.isEmpty() ? agentName : hostname);
            req.setHostname(hostname);

            // 获取本机IP
            String localIp = NetUtil.getLocalhostStr();
            req.setIp(localIp);

            // 平台后续通过 IP 和端口向本执行器下发运维任务。
            req.setAgentPort(agentPort);

            // 获取操作系统信息
            String osName = new OsInfo().getName();
            req.setOs(osName);

            // Redis配置判空，防止NPE
            AgentProperties.RedisConfig redisConfig = properties.getRedis();
            if (redisConfig == null) {
                log.error("注册失败：redis配置未配置，请检查配置文件");
                return false;
            }
            Map<String, String> redisInfo = new HashMap<>();
            redisInfo.put("host", redisConfig.getHost());
            redisInfo.put("port", String.valueOf(redisConfig.getPort()));
            req.setRedisInfo(redisInfo);

            // 平台地址校验
            String platformUrl = properties.getPlatformUrl();
            if (platformUrl == null || platformUrl.trim().isEmpty()) {
                log.error("注册失败：platformUrl 配置为空，请检查配置文件");
                return false;
            }

            // 发起远程注册请求
            Map<String, Object> result = platformClient.register(req, platformUrl);
            if (result == null) {
                log.error("注册失败：平台返回结果为null");
                return false;
            }

            Object data = result.get("data");
            Map<?, ?> dataMap = null;
            if (data instanceof Map) {
                dataMap = (Map<?, ?>) data;
            } else {
                log.error("注册失败：平台返回data格式不符合预期，result={}", result);
                return false;
            }

            // 读取返回凭证，校验类型
            Object tokenObj = dataMap.get("token");
            Object agentIdObj = dataMap.get("agentId");
            if (!(tokenObj instanceof String) || !(agentIdObj instanceof String)) {
                log.error("注册失败：token或agentId为空或类型错误，dataMap={}", dataMap);
                return false;
            }
            String token = (String) tokenObj;
            String agentId = (String) agentIdObj;

            this.token = token;
            this.agentId = agentId;

            // 文件写入单独try-catch：注册成功，写文件失败不影响返回true
            try {
                FileUtil.writeString(agentId + "|" + token, tokenFile, StandardCharsets.UTF_8);
                log.info("注册成功，agentId={}", agentId);
            } catch (Exception fileEx) {
                log.warn("注册成功，但本地写入token文件失败，agentId={}", agentId, fileEx);
            }
            initializeOrVerifyConfigSnapshot();
            return true;

        } /*catch (java.net.UnknownHostException e) {
            log.error("注册异常：解析主机/网卡信息失败", e);
        } catch (java.io.IOException e) {
            log.error("注册异常：网络请求IO异常，无法连接平台", e);
        } */catch (NullPointerException e) {
            log.error("注册异常：配置项缺失空指针，请检查agent配置", e);
        } catch (ClassCastException e) {
            log.error("注册异常：平台返回数据类型转换异常", e);
        } catch (Exception e) {
            log.error("注册请求未知异常", e);
        }
        return false;

    }

    /**
     * 注册身份建立后初始化或校验配置快照。
     * 校验失败不阻止心跳，但后续配置相关运维操作会因摘要不一致而失败。
     */
    private void initializeOrVerifyConfigSnapshot() {
        boolean success = configSnapshotService.initializeOrVerify(agentId, token);
        if (!success) {
            log.error("执行器身份已生效，但配置快照尚未通过校验，agentId={}", agentId);
        }
    }

    /** 获取当前执行器认证Token。 */
    @Override
    public String getToken() {
        return token;
    }

    /** 获取平台分配的执行器编号。 */
    @Override
    public String getAgentId() {
        return agentId;
    }
}
