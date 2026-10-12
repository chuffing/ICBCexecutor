package com.icbc.nbfh.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 本机运维任务线程池配置。
 *
 * 单台服务器上的高风险运维任务必须串行执行，不能阻塞心跳线程。
 */
@Configuration
public class OperationTaskExecutorConfig {

    /**
     * 创建只有一个工作线程的运维任务执行器。
     */
    @Bean(name = "operationTaskExecutor")
    public ThreadPoolTaskExecutor operationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("redis-operation-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
