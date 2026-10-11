package org.sopt.hashi.user.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.error.ErrorCode;
import org.sopt.hashi.user.code.UserErrorCode;
import org.sopt.hashi.user.collection.service.CollectionMapSnapshotStore.Snapshot;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

class CollectionMapQueryServiceTest {
    private static final Long COLLECTION_ID = 987654321L;
    private static final String PRIVATE_DETAIL = "collectionId=987654321 SELECT private_sql key=synthetic-secret";
    private static final String PRIVATE_CAUSE = "private-address 35.654321 139.123456";
    private static final String REQUEST_ID = "existing-request-context";
    private final CollectionMapSnapshotStore snapshots = mock(CollectionMapSnapshotStore.class);
    private final RestaurantPort restaurants = mock(RestaurantPort.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final CollectionMapQueryService service = new CollectionMapQueryService(snapshots, restaurants,
            currentUser, Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC));
    private final Logger logger = (Logger) LoggerFactory.getLogger(CollectionMapQueryService.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Level previousLevel;
    private Map<String, String> previousMdc;

    @BeforeEach
    void 진단_로그와_기존_요청_문맥을_준비한다() {
        previousLevel = logger.getLevel();
        previousMdc = MDC.getCopyOfContextMap();
        MDC.put("requestId", REQUEST_ID);
        logger.setLevel(Level.WARN);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void 로그와_요청_문맥을_복원한다() {
        logger.detachAppender(logs);
        logs.stop();
        logger.setLevel(previousLevel);
        if (previousMdc == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(previousMdc);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void snapshot_읽기와_최종검사의_DB_실패는_원인을_보존하고_안전한_WARN_한건을_남긴다(boolean validate) {
        assertSnapshotFailure(validate, new DataAccessResourceFailureException(
                PRIVATE_DETAIL, new IllegalStateException(PRIVATE_CAUSE)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void snapshot_읽기와_최종검사의_transaction_실패는_원인을_보존하고_안전한_WARN_한건을_남긴다(boolean validate) {
        assertSnapshotFailure(validate, new CannotCreateTransactionException(
                PRIVATE_DETAIL, new IllegalStateException(PRIVATE_CAUSE)));
    }

    @ParameterizedTest
    @MethodSource("expectedSnapshotErrors")
    void snapshot의_예상된_접근권한_버전_상한_예외는_변경하거나_장애로_기록하지_않는다(
            boolean validate, ErrorCode code) {
        BusinessException failure = new BusinessException(code);
        failSnapshot(validate, failure);

        assertThatThrownBy(() -> service.getMarkers(COLLECTION_ID)).isSameAs(failure);
        assertThat(logs.list).isEmpty();
        verifyNoInteractions(restaurants);
    }

    private void assertSnapshotFailure(boolean validate, RuntimeException failure) {
        failSnapshot(validate, failure);

        assertThatThrownBy(() -> service.getMarkers(COLLECTION_ID))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(UserErrorCode.COLLECTION_MAP_UNAVAILABLE);
                    assertThat(error.getCause()).isSameAs(failure);
                });
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).isEqualTo(
                    "Collection map failed. operation=collection-map-snapshot exceptionType="
                            + failure.getClass().getSimpleName());
            assertThat(event.getArgumentArray()).containsExactly(failure.getClass().getSimpleName());
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getFormattedMessage()).doesNotContain(PRIVATE_DETAIL, PRIVATE_CAUSE,
                    COLLECTION_ID.toString(), "private_sql", "synthetic-secret", "35.654321", "139.123456");
            assertThat(event.getMDCPropertyMap()).containsEntry("requestId", REQUEST_ID);
        });
        assertThat(MDC.get("requestId")).isEqualTo(REQUEST_ID);
        verifyNoInteractions(restaurants);
    }

    private void failSnapshot(boolean validate, RuntimeException failure) {
        if (validate) {
            Snapshot snapshot = new Snapshot(COLLECTION_ID, 7L, List.of());
            given(snapshots.read(COLLECTION_ID, null)).willReturn(snapshot);
            doThrow(failure).when(snapshots).validate(snapshot, null);
        } else {
            given(snapshots.read(COLLECTION_ID, null)).willThrow(failure);
        }
    }

    private static Stream<Arguments> expectedSnapshotErrors() {
        return Stream.of(
                Arguments.of(false, UserErrorCode.COLLECTION_NOT_FOUND),
                Arguments.of(false, UserErrorCode.COLLECTION_MAP_UNAVAILABLE),
                Arguments.of(true, UserErrorCode.COLLECTION_NOT_FOUND),
                Arguments.of(true, CommonErrorCode.CONFLICT));
    }
}
