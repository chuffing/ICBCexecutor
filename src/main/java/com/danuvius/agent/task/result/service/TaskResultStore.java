package com.danuvius.agent.task.result.service;

import com.danuvius.agent.task.result.model.TaskResultRecord;

import java.util.List;

/**
 * 运维步骤结果的本地持久化接口。
 */
public interface TaskResultStore {

    /** 保存或覆盖同一任务步骤的最终结果。 */
    void save(TaskResultRecord record);

    /** 查询指定任务步骤已经落盘的最终结果。 */
    TaskResultRecord find(String taskNo, int stepNo);

    /** 读取所有尚未得到平台确认的结果。 */
    List<TaskResultRecord> findUnreported();

    /** 将指定结果标记为平台已经确认。 */
    void markReported(TaskResultRecord record);
}
