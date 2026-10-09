package com.danuvius.agent.sentinel.model;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 Sentinel 目标主库参数的兼容模式和严格校验。 */
class StartSentinelTaskParametersTest {

    /** 未传目标主库时应保留旧调用方的兼容模式。 */
    @Test
    void shouldAllowCompatibilityMode() {
        StartSentinelTaskParameters result =
                StartSentinelTaskParameters.from(Collections.emptyMap());
        assertFalse(result.hasExpectedMaster());
    }

    /** 同时传入主库地址和端口时应返回规范化参数。 */
    @Test
    void shouldReadExpectedMaster() {
        Map<String, String> params = new HashMap<>();
        params.put("expectedMasterHost", " 172.20.10.5 ");
        params.put("expectedMasterPort", "6379");
        StartSentinelTaskParameters result = StartSentinelTaskParameters.from(params);
        assertTrue(result.hasExpectedMaster());
        assertEquals("172.20.10.5", result.getExpectedMasterHost());
        assertEquals(Integer.valueOf(6379), result.getExpectedMasterPort());
    }

    /** 地址和端口只传一个时必须拒绝，防止检查条件不完整。 */
    @Test
    void shouldRejectPartialExpectedMaster() {
        Map<String, String> params = new HashMap<>();
        params.put("expectedMasterHost", "172.20.10.5");
        assertThrows(IllegalArgumentException.class,
                () -> StartSentinelTaskParameters.from(params));
    }
}
