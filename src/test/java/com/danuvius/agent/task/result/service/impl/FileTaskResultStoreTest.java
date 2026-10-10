package com.danuvius.agent.task.result.service.impl;

import com.danuvius.agent.config.AgentProperties;
import com.danuvius.agent.task.result.model.TaskResultRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证任务结果在执行器重启前后都可以恢复和继续补报。 */
class FileTaskResultStoreTest {

    @TempDir
    Path temporaryDirectory;

    /** 未确认结果落盘后，新建存储对象仍能读取；确认状态也会持久化。 */
    @Test
    void shouldPersistResultAndReportedState() {
        AgentProperties properties = new AgentProperties();
        properties.setTaskResultDir(temporaryDirectory.toString());
        FileTaskResultStore firstStore = new FileTaskResultStore(properties);

        TaskResultRecord record = resultRecord();
        firstStore.save(record);
        assertEquals(1, firstStore.findUnreported().size());

        // 模拟执行器进程重启：重新创建存储对象并从磁盘读取同一结果。
        FileTaskResultStore restartedStore = new FileTaskResultStore(properties);
        TaskResultRecord restored = restartedStore.find("TASK202610100001", 1);
        assertNotNull(restored);
        assertFalse(restored.isReported());
        assertEquals("STOP_SENTINEL", restored.getOperationType());

        restartedStore.markReported(restored);
        TaskResultRecord confirmed = restartedStore.find("TASK202610100001", 1);
        assertTrue(confirmed.isReported());
        assertNotNull(confirmed.getReportedTime());
        assertTrue(restartedStore.findUnreported().isEmpty());
    }

    /** 创建测试所需的完整结果记录。 */
    private TaskResultRecord resultRecord() {
        TaskResultRecord record = new TaskResultRecord();
        record.setTaskNo("TASK202610100001");
        record.setStepNo(1);
        record.setAgentId("redis-node-01");
        record.setOperationType("STOP_SENTINEL");
        record.setStatus("SUCCESS");
        record.setResult("{\"success\":true}");
        record.setFinishTime(System.currentTimeMillis());
        record.setReported(false);
        return record;
    }
}
