package org.sopt.hashi.media.internal.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.sopt.hashi.media.domain.ImageAssetRepository;
import org.sopt.hashi.media.domain.MediaPipelineConfigRepository;
import org.sopt.hashi.media.internal.metrics.MediaAssetMetricsRefresher;
import org.sopt.hashi.media.internal.metrics.MediaPipelineMetrics;
import org.sopt.hashi.media.internal.spec.MediaSpecRegistry;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

class MediaStartupRecoveryTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-27T09:00:00Z"), ZoneOffset.UTC);

    @Test
    void Ready_event는_각_시작_작업을_한번씩_실행한다() {
        try (Fixture fixture = new Fixture()) {
            assertThatCode(fixture::publishReadyEvent).doesNotThrowAnyException();

            fixture.verifyAttempts(1, 1, 1);
            verify(fixture.metrics).recordEprResubmission("startup");
            assertThat(fixture.transactions.readOnly).containsExactly(true);
            assertThat(fixture.transactions.commits).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @EnumSource(StartupTask.class)
    void 시작_작업_실패는_다른_작업과_다음_예약_실행을_막지_않는다(StartupTask task) {
        try (Fixture fixture = new Fixture()) {
            fixture.failOnce(task);

            assertThatCode(fixture::publishReadyEvent).doesNotThrowAnyException();
            fixture.verifyAttempts(1, 1, 1);

            // Invoke the existing scheduled entry points; no wall-clock scheduler is started here.
            assertThatCode(fixture::runScheduledMethods).doesNotThrowAnyException();
            fixture.verifyAttempts(2, 2, 2);
            verify(fixture.metrics).recordEprResubmission("scheduled");
            assertThat(fixture.registry.get("hashi.media.processing.stalled")
                    .gauge().value()).isEqualTo(7D);
        }
    }

    @ParameterizedTest
    @EnumSource(TransactionFailure.class)
    void transaction_proxy의_begin과_commit_실패도_격리하고_다음_예약_실행은_성공한다(
            TransactionFailure failure) {
        try (Fixture fixture = new Fixture()) {
            fixture.transactions.failure = failure;

            assertThatCode(fixture::publishReadyEvent).doesNotThrowAnyException();
            fixture.verifyAttempts(1, 1, failure == TransactionFailure.BEGIN ? 0 : 1);
            assertThat(fixture.transactions.readOnly).containsExactly(true);

            assertThatCode(fixture::runScheduledMethods).doesNotThrowAnyException();
            fixture.verifyAttempts(2, 2, failure == TransactionFailure.BEGIN ? 1 : 2);
            assertThat(fixture.transactions.readOnly).containsExactly(true, true);
            assertThat(fixture.transactions.commits)
                    .isEqualTo(failure == TransactionFailure.BEGIN ? 1 : 2);
            assertThat(fixture.registry.get("hashi.media.processing.stalled")
                    .gauge().value()).isEqualTo(7D);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "missing"})
    void queue가_꺼지거나_설정되지_않으면_시작과_예약_작업_bean을_등록하지_않는다(String enabled) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            if (!enabled.equals("missing")) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                        "test", Map.of("hashi.media.queue.enabled", enabled)));
            }
            context.register(MediaStartupRecovery.class, MediaEventPublicationRecovery.class,
                    MediaProcessingRecoveryScheduler.class, MediaAssetMetricsRefresher.class);

            assertThatCode(context::refresh).doesNotThrowAnyException();
            assertThat(context.getBeansOfType(MediaStartupRecovery.class)).isEmpty();
            assertThat(context.getBeansOfType(MediaEventPublicationRecovery.class)).isEmpty();
            assertThat(context.getBeansOfType(MediaProcessingRecoveryScheduler.class)).isEmpty();
            assertThat(context.getBeansOfType(MediaAssetMetricsRefresher.class)).isEmpty();
        }
    }

    private enum StartupTask { PUBLICATIONS, PROCESSING, METRICS }

    private enum TransactionFailure { BEGIN, COMMIT }

    private static final class Fixture implements AutoCloseable {

        private final IncompleteEventPublications publications = mock(IncompleteEventPublications.class);
        private final MediaProcessingRecoveryReader reader = mock(MediaProcessingRecoveryReader.class);
        private final ImageAssetRepository assets = mock(ImageAssetRepository.class);
        private final MediaPipelineMetrics metrics = mock(MediaPipelineMetrics.class);
        private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        private final FailingTransactionManager transactions = new FailingTransactionManager();
        private final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        private final MediaEventPublicationRecovery publicationRecovery;
        private final MediaProcessingRecoveryScheduler processingRecovery;
        private final MediaAssetMetricsRefresher metricsRefresher;

        private Fixture() {
            MediaRecoveryProperties properties = new MediaRecoveryProperties(
                    true, null, null, 0, null, null, 0, 0, 0, null, null, null, null, null);
            when(assets.countStalledProcessing(any(), any(), any())).thenReturn(7L);
            publicationRecovery = new MediaEventPublicationRecovery(
                    publications, properties, metrics, CLOCK);
            processingRecovery = new MediaProcessingRecoveryScheduler(properties, reader,
                    mock(MediaProcessingRecoveryTransactionService.class), metrics, CLOCK);
            MediaAssetMetricsRefresher target = new MediaAssetMetricsRefresher(assets,
                    mock(MediaPipelineConfigRepository.class), mock(MediaSpecRegistry.class),
                    properties, registry, CLOCK);
            TransactionInterceptor interceptor = new TransactionInterceptor();
            interceptor.setTransactionManager(transactions);
            interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
            interceptor.afterPropertiesSet();
            ProxyFactory proxy = new ProxyFactory(target);
            proxy.setProxyTargetClass(true);
            proxy.addAdvice(interceptor);
            metricsRefresher = (MediaAssetMetricsRefresher) proxy.getProxy();

            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "test", Map.of("hashi.media.queue.enabled", "true")));
            context.registerBean(MediaEventPublicationRecovery.class, () -> publicationRecovery);
            context.registerBean(MediaProcessingRecoveryScheduler.class, () -> processingRecovery);
            context.registerBean(MediaAssetMetricsRefresher.class, () -> metricsRefresher);
            context.register(MediaStartupRecovery.class);
            context.refresh();
        }

        private void publishReadyEvent() {
            // Tests Spring event dispatch, not a full SpringApplication startup or AWS/DB readiness.
            context.publishEvent(new ApplicationReadyEvent(
                    new SpringApplication(MediaStartupRecovery.class), new String[0], context,
                    Duration.ZERO));
        }

        private void failOnce(StartupTask task) {
            RuntimeException unavailable = new IllegalStateException("temporary backend failure");
            switch (task) {
                case PUBLICATIONS -> doThrow(unavailable).doNothing().when(publications)
                        .resubmitIncompletePublications(any());
                case PROCESSING -> when(reader.findBatch(any(), any(), anyInt(), any(), anyInt()))
                        .thenThrow(unavailable).thenReturn(List.of());
                case METRICS -> when(assets.countByProcessingStatus())
                        .thenThrow(unavailable).thenReturn(List.of());
            }
        }

        private void runScheduledMethods() {
            publicationRecovery.resubmitOldPublications();
            processingRecovery.scheduleRecovery();
            metricsRefresher.refresh();
        }

        private void verifyAttempts(int publicationCount, int processingCount, int metricsCount) {
            verify(publications, times(publicationCount)).resubmitIncompletePublications(any());
            verify(reader, times(processingCount)).findBatch(any(), any(), anyInt(), any(), anyInt());
            verify(assets, times(metricsCount)).countByProcessingStatus();
        }

        @Override
        public void close() {
            context.close();
            registry.close();
        }
    }

    private static final class FailingTransactionManager implements PlatformTransactionManager {

        private final List<Boolean> readOnly = new ArrayList<>();
        private TransactionFailure failure;
        private int commits;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            readOnly.add(definition.isReadOnly());
            if (failure == TransactionFailure.BEGIN) {
                failure = null;
                throw new CannotCreateTransactionException("temporary begin failure");
            }
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            commits++;
            if (failure == TransactionFailure.COMMIT) {
                failure = null;
                throw new TransactionSystemException("temporary commit failure");
            }
        }

        @Override
        public void rollback(TransactionStatus status) {
            // No resource is allocated by this transaction-boundary test double.
        }
    }
}
