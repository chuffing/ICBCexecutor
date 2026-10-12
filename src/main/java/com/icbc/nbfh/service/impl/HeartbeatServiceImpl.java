package com.icbc.nbfh.service.impl;

import cn.hutool.core.net.NetUtil;
import com.icbc.nbfh.service.client.PlatformClient;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.dto.HeartbeatRequest;
import com.icbc.nbfh.service.HeartbeatService;
import com.icbc.nbfh.service.RegisterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class HeartbeatServiceImpl implements HeartbeatService {

    @Autowired
    private PlatformClient platformClient;

    @Autowired
    private RegisterService registerService;

    @Autowired
    private AgentProperties properties;

    @Override
    public void sendHeartbeat() {
        if (registerService.getToken() == null) {
            return;
        }
        try {
            HeartbeatRequest req = new HeartbeatRequest();
            req.setToken(registerService.getToken());
            req.setAgentId(registerService.getAgentId());
            req.setStatus("UP");
            req.setHostname(new cn.hutool.system.HostInfo().getName());
            req.setIp(NetUtil.getLocalhostStr());
            req.setLastCollectTime(System.currentTimeMillis() - properties.getCollectInterval());

            platformClient.heartbeat(req,
                    properties.getPlatformUrl(), registerService.getToken());
        } catch (Exception e) {
            log.error("心跳发送失败", e);
        }
    }
}
