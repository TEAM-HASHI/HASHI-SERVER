package org.sopt.hashi.config;

import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 도메인 이벤트 리스너({@code @ApplicationModuleListener} = {@code @Async} + REQUIRES_NEW) 전용 실행기.
 * 프로젝트에 media·backfill용 executor 빈이 여럿이라 Spring의 기본 executor 탐색(유일한 TaskExecutor → "taskExecutor" 빈)이
 * 실패해 호출마다 스레드를 새로 만드는 SimpleAsyncTaskExecutor로 떨어지므로, AsyncConfigurer로 기본 executor를 고정한다.
 * 거절 정책이 CallerRuns인 이유: 리스너 제출은 발행 트랜잭션의 afterCommit에서 일어나는데, 제출이 거절되면 그 예외가
 * 커밋 호출자(요청 스레드)로 전파돼 이미 커밋된 요청이 500으로 끝난다. 호출자 스레드에서 실행해도 리스너는 REQUIRES_NEW라 안전하다.
 * 리스너 예외는 호출자에게 전파되지 않으므로 여기서 ERROR로 남긴다 — publication은 미완료로 남아 재제출기가 다시 보낸다.
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    public static final String DOMAIN_EVENT_EXECUTOR = "domainEventExecutor";

    private static final int CORE_POOL_SIZE = 2;
    private static final int MAX_POOL_SIZE = 4;
    private static final int QUEUE_CAPACITY = 100;
    private static final int SHUTDOWN_AWAIT_SECONDS = 20;

    @Bean(name = DOMAIN_EVENT_EXECUTOR)
    public ThreadPoolTaskExecutor domainEventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CORE_POOL_SIZE);
        executor.setMaxPoolSize(MAX_POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix("domain-event-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 종료 시 큐에 남은 리스너를 마저 실행한다 — 못 끝낸 publication은 미완료로 남아 재제출된다
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(SHUTDOWN_AWAIT_SECONDS);
        return executor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return domainEventExecutor();
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) -> log.error(
                "도메인 이벤트 리스너 실패 — publication이 미완료로 남아 재제출된다. listener={}.{}, event={}",
                method.getDeclaringClass().getSimpleName(), method.getName(), Arrays.toString(params), ex);
    }
}
