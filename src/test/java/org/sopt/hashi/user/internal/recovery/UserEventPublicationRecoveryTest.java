package org.sopt.hashi.user.internal.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.sopt.hashi.user.UserWithdrawnEvent;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.IncompleteEventPublications;

class UserEventPublicationRecoveryTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T09:00:00Z"), ZoneOffset.UTC);

    private final IncompleteEventPublications incompletePublications = mock(IncompleteEventPublications.class);
    private final UserEventPublicationRecovery recovery =
            new UserEventPublicationRecovery(incompletePublications, CLOCK);

    @Test
    void 기동_시_탈퇴_이벤트의_미완료_publication만_배치_크기까지_재제출한다() {
        recovery.resubmitOnStartup();

        Predicate<EventPublication> predicate = capturedPredicate();
        assertThat(predicate.test(publication("other-module-event", Instant.now(CLOCK)))).isFalse();
        for (int index = 0; index < UserEventPublicationRecovery.RESUBMIT_BATCH_SIZE; index++) {
            assertThat(predicate.test(publication(new UserWithdrawnEvent((long) index), Instant.now(CLOCK)))).isTrue();
        }
        assertThat(predicate.test(publication(new UserWithdrawnEvent(999L), Instant.now(CLOCK)))).isFalse();
    }

    @Test
    void 주기_실행은_기준_시간보다_오래된_publication만_재제출한다() {
        recovery.resubmitOldPublications();

        Predicate<EventPublication> predicate = capturedPredicate();
        assertThat(predicate.test(publication(
                new UserWithdrawnEvent(1L),
                Instant.now(CLOCK).minus(UserEventPublicationRecovery.RESUBMIT_AGE)))).isTrue();
        assertThat(predicate.test(publication(
                new UserWithdrawnEvent(1L),
                Instant.now(CLOCK).minus(UserEventPublicationRecovery.RESUBMIT_AGE).plusSeconds(1)))).isFalse();
    }

    @SuppressWarnings("unchecked")
    private Predicate<EventPublication> capturedPredicate() {
        ArgumentCaptor<Predicate<EventPublication>> captor = ArgumentCaptor.forClass(Predicate.class);
        verify(incompletePublications).resubmitIncompletePublications(captor.capture());
        return captor.getValue();
    }

    private EventPublication publication(Object event, Instant publicationDate) {
        EventPublication publication = mock(EventPublication.class);
        given(publication.getEvent()).willReturn(event);
        given(publication.getPublicationDate()).willReturn(publicationDate);
        return publication;
    }
}
