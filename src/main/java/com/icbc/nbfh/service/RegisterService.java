package com.icbc.nbfh.service;

import java.util.Map;

/**
 * Agent 注册服务
 */
public interface RegisterService {

    /**
     * 执行注册流程
     * @return true=注册成功或已有token, false=注册失败
     */
    boolean registerIfNeeded();

    /**
     * 获取当前 token
     */
    String getToken();

    /**
     * 获取当前 agentId
     */
    String getAgentId();
}
