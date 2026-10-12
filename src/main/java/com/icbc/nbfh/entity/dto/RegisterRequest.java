package com.icbc.nbfh.entity.dto;

import lombok.Data;

import java.util.Map;

/**
 * 注册请求
 */
@Data
public class RegisterRequest {

    /** 预设注册码 */
    private String registerCode;

    /** Agent 名称 */
    private String agentName;

    /** 主机名 */
    private String hostname;

    /** IP 地址 */
    private String ip;

    /** Agent 对外提供运维任务接口的端口 */
    private Integer agentPort;

    /** 操作系统 */
    private String os;

    /** 本机 Redis 信息 */
    private Map<String, String> redisInfo;
}
