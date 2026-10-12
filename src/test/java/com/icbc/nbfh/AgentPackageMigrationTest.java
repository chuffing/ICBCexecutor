package com.icbc.nbfh;

import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.config.WebConfig;
import com.icbc.nbfh.config.interceptor.PlatformTokenInterceptor;
import com.icbc.nbfh.controller.AgentTaskController;
import com.icbc.nbfh.controller.ConfigTaskController;
import com.icbc.nbfh.controller.TaskResultQueryController;
import com.icbc.nbfh.controller.VersionTaskController;
import com.icbc.nbfh.entity.TaskResultRecord;
import com.icbc.nbfh.service.CollectService;
import com.icbc.nbfh.service.RegisterService;
import com.icbc.nbfh.service.TaskResultStore;
import com.icbc.nbfh.service.client.PlatformClient;
import com.icbc.nbfh.service.impl.AgentTaskServiceImpl;
import com.icbc.nbfh.service.operation.*;
import com.icbc.nbfh.service.task.CollectTask;
import com.icbc.nbfh.service.task.HeartbeatTask;
import com.icbc.nbfh.service.task.TaskResultRetryTask;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 验证新根包能扫描全部运维组件，以及原结果查询协议仍然可用。 */
@SpringBootTest(classes = RedisMonitorAgentApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"agent.agent-name=bank-migration-test",
                "agent.heartbeat-interval=3600000", "agent.collect-interval=3600000",
                "agent.task-result-retry-interval=3600000"})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AgentPackageMigrationTest {
    @Autowired private ApplicationContext context;
    @Autowired private MockMvc mvc;
    // 阻止测试启动时注册、采集或访问实际平台和本地结果目录。
    @MockBean private RegisterService registration;
    @MockBean private CollectService collection;
    @MockBean private PlatformClient platformClient;
    @MockBean private TaskResultStore store;

    @Test
    void shouldScanControllersOperationsAndScheduledTasksFromNewRoot() {
        for (Class<?> type : Arrays.asList(AgentTaskController.class, ConfigTaskController.class,
                VersionTaskController.class, TaskResultQueryController.class,
                WebConfig.class, PlatformTokenInterceptor.class,
                PrecheckHostOperation.class, DeployConfigOperation.class, RestoreConfigOperation.class,
                DeployVersionOperation.class, SwitchVersionOperation.class, RollbackVersionOperation.class,
                StartRedisOperation.class, StopRedisOperation.class,
                StartSentinelOperation.class, StopSentinelOperation.class,
                CollectTask.class, HeartbeatTask.class, TaskResultRetryTask.class)) {
            assertNotNull(context.getBean(type), type.getName());
        }
        AgentTaskServiceImpl service = context.getBean(AgentTaskServiceImpl.class);
        ThreadPoolTaskExecutor executor = context.getBean("operationTaskExecutor", ThreadPoolTaskExecutor.class);
        assertSame(executor, ReflectionTestUtils.getField(service, "operationTaskExecutor"));
        assertEquals(1, executor.getMaxPoolSize());
        assertEquals("bank-migration-test", context.getBean(AgentProperties.class).getAgentName());
    }

    @Test
    void shouldKeepTaskResultQueryPathAuthorizationAndJsonFields() throws Exception {
        when(registration.getToken()).thenReturn("migration-token");
        TaskResultRecord record = new TaskResultRecord();
        record.setTaskNo("TASK202610120001");
        record.setStepNo(1);
        record.setAgentId("AGENT-A");
        record.setOperationType("STOP_REDIS");
        record.setStatus("SUCCESS");
        record.setResult("{\"success\":true}");
        when(store.find("TASK202610120001", 1)).thenReturn(record);
        mvc.perform(get("/api/agent/task-result").param("taskNo", record.getTaskNo()).param("stepNo", "1")
                        .header("Authorization", "Bearer migration-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.taskNo").value(record.getTaskNo()))
                .andExpect(jsonPath("$.stepNo").value(1)).andExpect(jsonPath("$.agentId").value("AGENT-A"))
                .andExpect(jsonPath("$.operationType").value("STOP_REDIS"))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.result").value("{\"success\":true}"));
        mvc.perform(get("/api/agent/task-result").param("taskNo", record.getTaskNo()).param("stepNo", "1")
                        .header("Authorization", "Bearer wrong-token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/agent/task-result").param("taskNo", record.getTaskNo()).param("stepNo", "2")
                        .header("Authorization", "Bearer migration-token"))
                .andExpect(status().isNotFound());
    }
}
