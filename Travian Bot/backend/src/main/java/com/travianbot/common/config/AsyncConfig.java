package com.travianbot.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Async + scheduling configuration.
 * <p>
 * {@code botExecutor} is the thread pool for long-running bot work (Playwright sessions, periodic scans).
 * One browser/account is expensive; keep the pool small and rely on per-account queues for ordering.
 * <p>
 * {@code proxyTargetClass = true} forces CGLIB (subclass) proxies for {@code @Async} beans. Without
 * it, a notifier that implements an interface (e.g. {@code DiscordNotifier implements EventNotifier})
 * gets a JDK dynamic proxy typed only as the interface, so injecting it by its concrete class — which
 * the notifier's own settings controller does for {@code sendTest()} — fails at startup. Subclass
 * proxies keep both the concrete type and the interface, so autowiring by either still resolves.
 */
@Configuration
@EnableAsync(proxyTargetClass = true)
@EnableScheduling
public class AsyncConfig {

    @Bean(name = "botExecutor")
    public Executor botExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("bot-");
        executor.initialize();
        return executor;
    }
}
