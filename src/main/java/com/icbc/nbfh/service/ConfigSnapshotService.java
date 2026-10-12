package com.icbc.nbfh.service;

/**
 * 首次注册时建立本机配置备份，并与平台基准配置保持一致。
 */
public interface ConfigSnapshotService {

    /**
     * 平台没有快照时初始化；平台已有快照时只校验本机备份。
     *
     * @param agentId 平台分配的执行器编号
     * @param token 执行器认证Token
     * @return 初始化或校验是否成功
     */
    boolean initializeOrVerify(String agentId, String token);
}
