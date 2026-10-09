package com.danuvius.agent;

import com.danuvius.agent.registration.service.RegisterService;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * Spring 上下文工具类，用于在非 Spring 管理的类中获取 Bean
 */
@Component
public class AgentContext implements ApplicationContextAware {

    private static ApplicationContext context;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        context = applicationContext;
    }

    public static <T> T getBean(Class<T> clazz) {
        return context.getBean(clazz);
    }

    public static String getToken() {
        return getBean(RegisterService.class).getToken();
    }

    public static String getAgentId() {
        return getBean(RegisterService.class).getAgentId();
    }
}
