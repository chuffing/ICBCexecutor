package com.danuvius.agent.redis.model;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * START_REDIS 拓扑参数校验测试。
 */
class StartRedisTaskParametersTest {

    /** 验证旧调用方不传参数时仍进入兼容模式。 */
    @Test
    void shouldKeepCompatibilityModeWhenExpectedRoleIsMissing() {
        StartRedisTaskParameters parameters = StartRedisTaskParameters.from(
                Collections.emptyMap()
        );

        assertFalse(parameters.hasExpectedTopology());
    }

    /** 验证 MASTER 期望不需要主库地址。 */
    @Test
    void shouldAcceptMasterExpectation() {
        Map<String, String> params = new HashMap<>();
        params.put("expectedRole", "master");

        StartRedisTaskParameters parameters = StartRedisTaskParameters.from(params);

        assertTrue(parameters.hasExpectedTopology());
        assertEquals("MASTER", parameters.getExpectedRole());
    }

    /** 验证 REPLICA 期望会保存主库地址和端口。 */
    @Test
    void shouldAcceptReplicaExpectation() {
        Map<String, String> params = new HashMap<>();
        params.put("expectedRole", "REPLICA");
        params.put("expectedMasterHost", "172.20.10.12");
        params.put("expectedMasterPort", "6379");

        StartRedisTaskParameters parameters = StartRedisTaskParameters.from(params);

        assertEquals("REPLICA", parameters.getExpectedRole());
        assertEquals("172.20.10.12", parameters.getExpectedMasterHost());
        assertEquals(Integer.valueOf(6379), parameters.getExpectedMasterPort());
    }

    /** 验证 REPLICA 缺少主库地址时会拒绝任务。 */
    @Test
    void shouldRejectReplicaWithoutMasterHost() {
        Map<String, String> params = new HashMap<>();
        params.put("expectedRole", "REPLICA");
        params.put("expectedMasterPort", "6379");

        assertThrows(
                IllegalArgumentException.class,
                () -> StartRedisTaskParameters.from(params)
        );
    }

    /** 验证非法端口不会进入启动阶段。 */
    @Test
    void shouldRejectInvalidMasterPort() {
        Map<String, String> params = new HashMap<>();
        params.put("expectedRole", "REPLICA");
        params.put("expectedMasterHost", "172.20.10.12");
        params.put("expectedMasterPort", "70000");

        assertThrows(
                IllegalArgumentException.class,
                () -> StartRedisTaskParameters.from(params)
        );
    }

    /** 验证 MASTER 任务不能混入副本主库参数。 */
    @Test
    void shouldRejectMasterWithReplicaParameters() {
        Map<String, String> params = new HashMap<>();
        params.put("expectedRole", "MASTER");
        params.put("expectedMasterHost", "172.20.10.12");

        assertThrows(
                IllegalArgumentException.class,
                () -> StartRedisTaskParameters.from(params)
        );
    }

    /** 验证缺少角色时不能单独传入主库地址。 */
    @Test
    void shouldRejectMasterHostWithoutExpectedRole() {
        Map<String, String> params = new HashMap<>();
        params.put("expectedMasterHost", "172.20.10.12");

        assertThrows(
                IllegalArgumentException.class,
                () -> StartRedisTaskParameters.from(params)
        );
    }
}
