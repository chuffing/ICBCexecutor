package com.icbc.nbfh.service;

import com.icbc.nbfh.service.client.PlatformClient;
import com.icbc.nbfh.common.constant.OperationType;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.service.impl.FileTaskResultStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证首次上报失败后，执行器能够从本地文件自动补报。 */
class TaskResultDeliveryServiceTest {

    @TempDir
    Path temporaryDirectory;

    /** 第一次网络异常保留未确认结果，下一次补报成功后才标记为已确认。 */
    @Test
    void shouldRetryPersistedResultAfterNetworkFailure() {
        AgentProperties properties = new AgentProperties();
        properties.setPlatformUrl("http://platform:8100");
        properties.setTaskResultDir(temporaryDirectory.toString());
        FileTaskResultStore store = new FileTaskResultStore(properties);
        PlatformClient platformClient = mock(PlatformClient.class);
        RegisterService registerService = mock(RegisterService.class);
        when(registerService.getAgentId()).thenReturn("redis-node-01");
        when(registerService.getToken()).thenReturn("token-value");

        doThrow(new IllegalStateException("network down"))
                .doNothing()
                .when(platformClient)
                .reportTaskResult(anyString(), anyInt(), anyString(),
                        any(OperationType.class), anyString(), anyString(),
                        anyString(), anyString());

        TaskResultDeliveryService service = new TaskResultDeliveryService(
                store, platformClient, properties, registerService);
        service.recordAndReport("TASK202610100001", 1,
                OperationType.STOP_SENTINEL, "SUCCESS", "{\"success\":true}");
        assertEquals(1, store.findUnreported().size());

        service.retryUnreportedResults();
        assertTrue(store.findUnreported().isEmpty());
        verify(platformClient, org.mockito.Mockito.times(2))
                .reportTaskResult(anyString(), anyInt(), anyString(),
                        any(OperationType.class), anyString(), anyString(),
                        anyString(), anyString());
    }
}
