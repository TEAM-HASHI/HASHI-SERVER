package org.sopt.hashi.config;

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
 * {@code @Async} 실행기. 기본 실행기는 호출마다 스레드를 새로 만들어 상한이 없으므로 풀로 제한한다.
 * 큐가 가득 차면 호출한 스레드가 직접 실행(CallerRunsPolicy)해 작업을 버리지 않고,
 * 종료 시에는 남은 작업을 잠시 기다려 재배포로 인한 유실을 줄인다.
 * 실행기를 {@code @Bean}으로 등록하는 이유: 종료 대기와 스레드 정리는 빈 소멸 콜백({@code destroy})에서 실행되므로,
 * {@link AsyncConfigurer}가 {@code new}로 만들어 돌려주기만 하면 Spring이 관리하지 않아 그 설정이 동작하지 않는다.
 * 첫 사용처는 매거진 카운터 갱신(MagazineMetaUpdater)이다.
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    static final String ASYNC_EXECUTOR = "asyncTaskExecutor";

    private static final int CORE_POOL_SIZE = 2;
    private static final int MAX_POOL_SIZE = 4;
    private static final int QUEUE_CAPACITY = 1000;
    private static final int AWAIT_TERMINATION_SECONDS = 10;

    @Bean(name = ASYNC_EXECUTOR)
    public ThreadPoolTaskExecutor asyncTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CORE_POOL_SIZE);
        executor.setMaxPoolSize(MAX_POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix("async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(AWAIT_TERMINATION_SECONDS);
        return executor;
    }

    /** {@code @Configuration}은 CGLIB 프록시라 이 호출은 새 객체가 아니라 위에서 등록한 싱글턴 빈을 돌려준다. */
    @Override
    public Executor getAsyncExecutor() {
        return asyncTaskExecutor();
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) ->
                log.error("비동기 작업 실패. method={}, params={}", method.getName(), params, ex);
    }
}
