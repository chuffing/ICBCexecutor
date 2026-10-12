package com.icbc.nbfh.config;

import com.icbc.nbfh.config.interceptor.PlatformTokenInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册执行器 HTTP 接口使用的公共 Web 组件。
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final PlatformTokenInterceptor platformTokenInterceptor;

    /**
     * 为平台调用的任务入口和结果查询入口统一添加 Token 校验。
     *
     * @param registry Spring MVC 拦截器注册器
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(platformTokenInterceptor)
                .addPathPatterns(
                        "/api/agent/task",
                        "/api/agent/config-task",
                        "/api/agent/version-task",
                        "/api/agent/task-result"
                );
    }
}
