package com.danuvius.agent.task.service;

import com.danuvius.agent.task.model.PlatformTask;

/**
 * Agent 运维任务执行服务
 */
public interface AgentTaskService {

    /**
     * 校验并异步提交一个平台任务。
     *
     * @throws IllegalArgumentException 任务内容不合法
     * @throws org.springframework.core.task.TaskRejectedException 当前已有任务正在执行
     */
    void submitTask(PlatformTask task);
}
