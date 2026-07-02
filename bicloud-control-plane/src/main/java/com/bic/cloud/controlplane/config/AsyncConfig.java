package com.bic.cloud.controlplane.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Executor for container deployments. A deploy can pull an image for minutes,
 * so it must never run on a request (Tomcat) or scheduler thread; the API and
 * self-healing enqueue here and return immediately. The pool also caps how
 * many image pulls hit the workers at once.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "deploymentExecutor")
    public ThreadPoolTaskExecutor deploymentExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("deploy-");
        return executor;
    }
}
