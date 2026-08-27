package org.sopt.hashi.media.internal.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.sopt.hashi.media.internal.event.MediaProcessingRequestedEvent;
import org.sopt.hashi.media.internal.metrics.MediaPipelineMetrics;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.IncompleteEventPublications;

class MediaEventPublicationRecoveryTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-27T09:00:00Z"),
            ZoneOffset.UTC
    );

    private final IncompleteEventPublications incompletePublications =
            mock(IncompleteEventPublications.class);
    private final MediaPipelineMetrics metrics = mock(MediaPipelineMetrics.class);

    @Test
    void 시작할_때_media_미완료_publication만_재제출한다() {
        MediaEventPublicationRecovery recovery = new MediaEventPublicationRecovery(
                incompletePublications, properties(true), metrics, CLOCK);

        recovery.resubmitOnStartup();

        ArgumentCaptor<Predicate<EventPublication>> captor = predicateCaptor();
        verify(incompletePublications).resubmitIncompletePublications(captor.capture());
        assertThat(captor.getValue().test(publication(
                new MediaProcessingRequestedEvent(UUID.randomUUID(), UUID.randomUUID()),
                Instant.now(CLOCK)))).isTrue();
        assertThat(captor.getValue().test(publication(
                "other-module-event",
                Instant.now(CLOCK)))).isFalse();
        verify(metrics).recordEprResubmission("startup");
    }

    @Test
    void 실행_중에는_기준_시간보다_오래된_publication만_재제출한다() {
        MediaEventPublicationRecovery recovery = new MediaEventPublicationRecovery(
                incompletePublications, properties(true), metrics, CLOCK);

        recovery.resubmitOldPublications();

        ArgumentCaptor<Predicate<EventPublication>> captor = predicateCaptor();
        verify(incompletePublications).resubmitIncompletePublications(captor.capture());
        MediaProcessingRequestedEvent event =
                new MediaProcessingRequestedEvent(UUID.randomUUID(), UUID.randomUUID());
        assertThat(captor.getValue().test(publication(
                event, Instant.now(CLOCK).minus(Duration.ofMinutes(1))))).isTrue();
        assertThat(captor.getValue().test(publication(
                event, Instant.now(CLOCK).minusSeconds(59)))).isFalse();
        assertThat(captor.getValue().test(publication(
                "other-module-event",
                Instant.now(CLOCK).minus(Duration.ofMinutes(10))))).isFalse();
        verify(metrics).recordEprResubmission("scheduled");
    }

    @Test
    void 복구가_중지되면_publication을_재제출하지_않는다() {
        MediaEventPublicationRecovery recovery = new MediaEventPublicationRecovery(
                incompletePublications, properties(false), metrics, CLOCK);

        recovery.resubmitOnStartup();
        recovery.resubmitOldPublications();

        verify(incompletePublications, never()).resubmitIncompletePublications(any());
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Predicate<EventPublication>> predicateCaptor() {
        return ArgumentCaptor.forClass(Predicate.class);
    }

    private EventPublication publication(Object event, Instant publicationDate) {
        EventPublication publication = mock(EventPublication.class);
        org.mockito.BDDMockito.given(publication.getEvent()).willReturn(event);
        org.mockito.BDDMockito.given(publication.getPublicationDate()).willReturn(publicationDate);
        return publication;
    }

    private MediaRecoveryProperties properties(boolean enabled) {
        return new MediaRecoveryProperties(
                enabled,
                Duration.ofMinutes(1),
                Duration.ofMinutes(1),
                Duration.ofMinutes(10),
                Duration.ofMinutes(15),
                3,
                100,
                10,
                Duration.ofSeconds(30),
                Duration.ofHours(24),
                Duration.ofHours(24),
                Duration.ofDays(7),
                Duration.ofDays(7)
        );
    }
}
