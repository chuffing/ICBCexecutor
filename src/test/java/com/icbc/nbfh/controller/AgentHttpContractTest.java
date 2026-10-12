package com.icbc.nbfh.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icbc.nbfh.common.constant.OperationType;
import com.icbc.nbfh.config.interceptor.PlatformTokenInterceptor;
import com.icbc.nbfh.entity.dto.PlatformTask;
import com.icbc.nbfh.service.AgentTaskService;
import com.icbc.nbfh.service.RegisterService;
import com.icbc.nbfh.service.ConfigPackageService;
import com.icbc.nbfh.service.VersionPackageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 对目录迁移前后必须兼容的三种任务入口及报文进行回归验证。 */
class AgentHttpContractTest {
    private AgentTaskService tasks;
    private VersionPackageService versions;
    private ConfigPackageService configs;
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        tasks = mock(AgentTaskService.class);
        versions = mock(VersionPackageService.class);
        configs = mock(ConfigPackageService.class);
        RegisterService registration = mock(RegisterService.class);
        when(registration.getToken()).thenReturn("migration-token");
        AgentTaskController task = new AgentTaskController();
        ConfigTaskController config = new ConfigTaskController();
        VersionTaskController version = new VersionTaskController();
        for (Object controller : Arrays.asList(task, config, version)) {
            ReflectionTestUtils.setField(controller, "agentTaskService", tasks);
        }
        ReflectionTestUtils.setField(config, "configPackageService", configs);
        ReflectionTestUtils.setField(version, "versionPackageService", versions);
        PlatformTokenInterceptor tokenInterceptor =
                new PlatformTokenInterceptor(registration, json);
        mvc = MockMvcBuilders.standaloneSetup(task, config, version)
                .addInterceptors(tokenInterceptor)
                .build();
    }

    private MockHttpServletRequestBuilder request(String endpoint) {
        String type = endpoint.equals("task") ? "STOP_REDIS"
                : endpoint.equals("version-task") ? "DEPLOY_VERSION" : "DEPLOY_CONFIG";
        String body = "{\"taskNo\":\"TASK202610120001\",\"stepNo\":1,\"taskType\":\"" + type + "\",\"params\":{}}";
        String path = "/api/agent/" + endpoint;
        if (endpoint.equals("task")) return post(path).contentType(MediaType.APPLICATION_JSON).content(body);
        MockMultipartFile task = new MockMultipartFile("task", "task.json", MediaType.APPLICATION_JSON_VALUE,
                body.getBytes(StandardCharsets.UTF_8));
        if (endpoint.equals("version-task")) {
            return multipart(path).file(task).file(new MockMultipartFile("file", "redis.tar.gz",
                    "application/gzip", new byte[]{1}));
        }
        return multipart(path).file(task)
                .file(new MockMultipartFile("redisFile", "redis.conf", "text/plain", new byte[]{1}))
                .file(new MockMultipartFile("sentinelFile", "sentinel.conf", "text/plain", new byte[]{2}));
    }

    @ParameterizedTest
    @ValueSource(strings = {"task", "version-task", "config-task"})
    void shouldKeepAcceptedResponseAndDispatchFields(String endpoint) throws Exception {
        String response = mvc.perform(request(endpoint).header("Authorization", "Bearer migration-token"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.taskNo").value("TASK202610120001"))
                .andExpect(jsonPath("$.status").value("ACCEPTED")).andReturn().getResponse().getContentAsString();
        JsonNode parsed = json.readTree(response);
        Set<String> fields = new HashSet<>();
        parsed.fieldNames().forEachRemaining(fields::add);
        assertEquals(new HashSet<>(Arrays.asList("taskNo", "status", "message")), fields);
        ArgumentCaptor<PlatformTask> task = ArgumentCaptor.forClass(PlatformTask.class);
        verify(tasks).submitTask(task.capture());
        assertEquals("TASK202610120001", task.getValue().requireTaskNo());
        assertEquals(1, task.getValue().requireStepNo());
        OperationType type = task.getValue().resolveOperationType();
        assertEquals(endpoint.equals("task") ? OperationType.STOP_REDIS
                : endpoint.equals("version-task") ? OperationType.DEPLOY_VERSION : OperationType.DEPLOY_CONFIG, type);
        if (endpoint.equals("version-task")) verify(versions).accept(any(), any());
        if (endpoint.equals("config-task")) verify(configs).accept(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"task", "version-task", "config-task"})
    void shouldStillRejectWrongTokenBeforeAnyBusinessOperation(String endpoint) throws Exception {
        mvc.perform(request(endpoint).header("Authorization", "Bearer wrong-token"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value("UNAUTHORIZED"));
        verifyNoInteractions(tasks, versions, configs);
    }
}
