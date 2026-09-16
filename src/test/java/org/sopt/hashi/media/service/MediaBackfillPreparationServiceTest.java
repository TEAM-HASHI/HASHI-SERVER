package org.sopt.hashi.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.sopt.hashi.media.MediaBackfillAssetInfo.State;
import org.sopt.hashi.media.MediaBackfillInspectionInfo;
import org.sopt.hashi.media.MediaBackfillReference;
import org.sopt.hashi.media.MediaBackfillSourceException;
import org.sopt.hashi.media.MediaBackfillTarget;
import org.sopt.hashi.media.code.MediaErrorCode;
import org.sopt.hashi.media.domain.ImageBindingStatus;
import org.sopt.hashi.media.domain.ImageProcessingStatus;
import org.sopt.hashi.media.domain.MediaCleanupStatus;
import org.sopt.hashi.media.domain.MediaPurpose;
import org.sopt.hashi.media.internal.backfill.BackfillOriginalCopy;
import org.sopt.hashi.media.internal.backfill.LegacyImageSource;
import org.sopt.hashi.media.internal.backfill.MediaBackfillIdentityFactory;
import org.sopt.hashi.media.internal.backfill.MediaBackfillProperties;
import org.sopt.hashi.media.internal.backfill.MediaBackfillStorage;
import org.sopt.hashi.media.internal.backfill.MediaBackfillStorageException;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class MediaBackfillPreparationServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-31T03:00:00Z"), ZoneOffset.UTC);
    private static final MediaBackfillReference REFERENCE =
            new MediaBackfillReference(MediaBackfillTarget.USER_PROFILE, 1L, "profiles/photo.jpg");
    private static final LegacyImageSource SOURCE =
            new LegacyImageSource("test-delivery", REFERENCE.legacyKey(), "source-v1", "etag", "image/jpeg", 1024);
    private static final MediaBackfillIdentityFactory IDENTITY_FACTORY = new MediaBackfillIdentityFactory();
    private static final String HASH = IDENTITY_FACTORY.create("USER", 1L, "PROFILE", SOURCE);
    private static final UUID ASSET_ID = UUID.fromString("88e6d685-9e64-49b3-a1e6-001757f633cf");
    private final MediaBackfillStorage storage = mock(MediaBackfillStorage.class);
    private final MediaBackfillReservationService reservations = mock(MediaBackfillReservationService.class);
    private final MediaBackfillTransactionService transactions = mock(MediaBackfillTransactionService.class);
    private final MediaBackfillPreparationService service = service(true, Map.of("storage", storage));

    @Test
    void 비활성화하면_조사와_준비가_모두_DB와_S3를_호출하지_않는다() {
        MediaBackfillPreparationService disabled = service(false, Map.of("storage", storage));

        assertUnavailable(() -> disabled.inspect(REFERENCE));
        assertUnavailable(() -> disabled.prepare(REFERENCE, HASH));
        verifyNoInteractions(storage, reservations, transactions);
    }

    @Test
    void 활성화됐어도_storage_adapter가_없으면_fail_closed한다() {
        MediaBackfillPreparationService unavailable = service(true, Map.of());

        assertUnavailable(() -> unavailable.inspect(REFERENCE));
        assertUnavailable(() -> unavailable.prepare(REFERENCE, HASH));
        verifyNoInteractions(storage, reservations, transactions);
    }

    @Test
    void 조사는_source와_기존_예약만_조회하고_변경하지_않는다() {
        when(storage.inspectSource(REFERENCE.legacyKey())).thenReturn(SOURCE);
        when(transactions.findReservation(MediaPurpose.PROFILE, HASH, SOURCE)).thenReturn(Optional.empty());

        MediaBackfillInspectionInfo inspection = service.inspect(REFERENCE);

        assertThat(inspection.identityHash()).isEqualTo(HASH);
        assertThat(inspection.purpose()).isEqualTo(REFERENCE.target().purpose());
        assertThat(inspection.asset()).isEmpty();
        verifyNoInteractions(reservations);
        verify(storage).inspectSource(REFERENCE.legacyKey());
        verify(transactions).findReservation(MediaPurpose.PROFILE, HASH, SOURCE);
        verifyNoMoreInteractions(storage, transactions);
    }

    @Test
    void 조사는_기존_예약을_조회해도_copy나_job을_생성하지_않는다() {
        when(storage.inspectSource(REFERENCE.legacyKey())).thenReturn(SOURCE);
        when(transactions.findReservation(MediaPurpose.PROFILE, HASH, SOURCE))
                .thenReturn(Optional.of(snapshot(ImageProcessingStatus.PROCESSING)));

        assertThat(service.inspect(REFERENCE).asset()).hasValueSatisfying(
                asset -> assertThat(asset.state()).isEqualTo(State.PROCESSING));
        verifyNoInteractions(reservations);
        verify(storage).inspectSource(REFERENCE.legacyKey());
        verifyNoMoreInteractions(storage);
    }

    @Test
    void 조사_뒤_source가_달라졌으면_새_예약이나_copy를_만들지_않는다() {
        when(storage.inspectSource(REFERENCE.legacyKey())).thenReturn(
                new LegacyImageSource("test-delivery", REFERENCE.legacyKey(),
                        "source-v2", "new-etag", "image/jpeg", 1024));

        assertThatThrownBy(() -> service.prepare(REFERENCE, HASH))
                .isInstanceOfSatisfying(MediaBackfillSourceException.class, exception ->
                        assertThat(exception.getReason())
                                .isEqualTo(MediaBackfillSourceException.Reason.SOURCE_CHANGED));
        verifyNoInteractions(reservations, transactions);
        verify(storage).inspectSource(REFERENCE.legacyKey());
        verifyNoMoreInteractions(storage);
    }

    @Test
    void 신규_준비는_예약한_asset의_copy를_완료하고_PROCESSING_snapshot을_반환한다() {
        BackfillAssetSnapshot pending = snapshot(ImageProcessingStatus.PENDING_UPLOAD);
        BackfillOriginalCopy copy = copy();
        when(storage.inspectSource(REFERENCE.legacyKey())).thenReturn(SOURCE);
        when(reservations.reserveOrReuse(MediaPurpose.PROFILE, HASH, SOURCE)).thenReturn(pending);
        when(storage.findOrCopyOriginal(ASSET_ID, HASH, SOURCE)).thenReturn(copy);
        when(transactions.completeCopy(ASSET_ID, copy)).thenReturn(snapshot(ImageProcessingStatus.PROCESSING));

        var prepared = service.prepare(REFERENCE, HASH);

        assertThat(prepared.assetId()).isEqualTo(ASSET_ID);
        assertThat(prepared.state()).isEqualTo(State.PROCESSING);
        verify(transactions).assertIssuanceAvailable();
        verify(transactions).completeCopy(ASSET_ID, copy);
    }

    @Test
    void 예약은_있어도_발급이_pause됐으면_copy를_시작하지_않는다() {
        when(storage.inspectSource(REFERENCE.legacyKey())).thenReturn(SOURCE);
        when(reservations.reserveOrReuse(MediaPurpose.PROFILE, HASH, SOURCE))
                .thenReturn(snapshot(ImageProcessingStatus.PENDING_UPLOAD));
        doThrow(new BusinessException(MediaErrorCode.PIPELINE_UNAVAILABLE))
                .when(transactions).assertIssuanceAvailable();

        assertUnavailable(() -> service.prepare(REFERENCE, HASH));

        verify(storage).inspectSource(REFERENCE.legacyKey());
        verifyNoMoreInteractions(storage);
        verify(transactions).assertIssuanceAvailable();
        verifyNoMoreInteractions(transactions);
    }

    @ParameterizedTest
    @MethodSource("nonCopyStates")
    void 처리중_완료_종료_연결_정리_상태는_새_copy나_job을_만들지_않는다(
            ImageProcessingStatus processing, ImageBindingStatus binding, MediaCleanupStatus cleanup, State expected) {
        when(storage.inspectSource(REFERENCE.legacyKey())).thenReturn(SOURCE);
        when(reservations.reserveOrReuse(MediaPurpose.PROFILE, HASH, SOURCE))
                .thenReturn(snapshot(processing, binding, cleanup, LocalDateTime.now(CLOCK).plusHours(1)));

        assertThat(service.prepare(REFERENCE, HASH).state()).isEqualTo(expected);

        verifyNoInteractions(transactions);
        verify(storage).inspectSource(REFERENCE.legacyKey());
        verifyNoMoreInteractions(storage);
    }

    @Test
    void 아직_PENDING이어도_만료_시각에_도달하면_copy하지_않는다() {
        when(storage.inspectSource(REFERENCE.legacyKey())).thenReturn(SOURCE);
        when(reservations.reserveOrReuse(MediaPurpose.PROFILE, HASH, SOURCE)).thenReturn(snapshot(
                ImageProcessingStatus.PENDING_UPLOAD, ImageBindingStatus.UNBOUND, MediaCleanupStatus.ACTIVE,
                LocalDateTime.now(CLOCK)));

        assertThat(service.prepare(REFERENCE, HASH).state()).isEqualTo(State.EXPIRED);
        verifyNoInteractions(transactions);
        verify(storage).inspectSource(REFERENCE.legacyKey());
        verifyNoMoreInteractions(storage);
    }

    @ParameterizedTest
    @EnumSource(MediaBackfillStorageException.Reason.class)
    void 조사_storage_실패는_고정된_공개_원인만_전달한다(MediaBackfillStorageException.Reason reason) {
        when(storage.inspectSource(REFERENCE.legacyKey())).thenThrow(new MediaBackfillStorageException(reason));

        assertSourceFailure(() -> service.inspect(REFERENCE), reason);
        assertSourceFailure(() -> service.prepare(REFERENCE, HASH), reason);
        verifyNoInteractions(reservations, transactions);
    }

    @ParameterizedTest
    @EnumSource(MediaBackfillStorageException.Reason.class)
    void copy_실패는_내부_예외를_노출하거나_DB_완료로_넘기지_않는다(
            MediaBackfillStorageException.Reason reason) {
        when(storage.inspectSource(REFERENCE.legacyKey())).thenReturn(SOURCE);
        when(reservations.reserveOrReuse(MediaPurpose.PROFILE, HASH, SOURCE))
                .thenReturn(snapshot(ImageProcessingStatus.PENDING_UPLOAD));
        when(storage.findOrCopyOriginal(any(), any(), any())).thenThrow(new MediaBackfillStorageException(reason));

        assertSourceFailure(() -> service.prepare(REFERENCE, HASH), reason);

        verify(transactions).assertIssuanceAvailable();
        verifyNoMoreInteractions(transactions);
    }

    private static Stream<Arguments> nonCopyStates() {
        return Stream.of(
                state(ImageProcessingStatus.PROCESSING, State.PROCESSING),
                state(ImageProcessingStatus.READY, State.READY),
                state(ImageProcessingStatus.FAILED, State.FAILED),
                state(ImageProcessingStatus.EXPIRED, State.EXPIRED),
                Arguments.of(ImageProcessingStatus.READY, ImageBindingStatus.BOUND, MediaCleanupStatus.ACTIVE,
                        State.BOUND),
                Arguments.of(ImageProcessingStatus.READY, ImageBindingStatus.RETIRED, MediaCleanupStatus.ACTIVE,
                        State.RETIRED),
                Arguments.of(ImageProcessingStatus.FAILED, ImageBindingStatus.UNBOUND, MediaCleanupStatus.PURGING,
                        State.PURGING),
                Arguments.of(ImageProcessingStatus.FAILED, ImageBindingStatus.UNBOUND, MediaCleanupStatus.PURGED,
                        State.PURGED));
    }

    private static Arguments state(ImageProcessingStatus processing, State state) {
        return Arguments.of(processing, ImageBindingStatus.UNBOUND, MediaCleanupStatus.ACTIVE, state);
    }

    private BackfillAssetSnapshot snapshot(ImageProcessingStatus processing) {
        return snapshot(processing, ImageBindingStatus.UNBOUND, MediaCleanupStatus.ACTIVE,
                LocalDateTime.now(CLOCK).plusHours(1));
    }

    private BackfillAssetSnapshot snapshot(ImageProcessingStatus processing, ImageBindingStatus binding,
                                           MediaCleanupStatus cleanup, LocalDateTime expiresAt) {
        return new BackfillAssetSnapshot(ASSET_ID, HASH, MediaPurpose.PROFILE,
                "media/originals/" + ASSET_ID + "/original", expiresAt, processing, binding, cleanup,
                null, null, null);
    }

    private BackfillOriginalCopy copy() {
        return new BackfillOriginalCopy("media/originals/" + ASSET_ID + "/original",
                "copy-v1", "copy-etag", "image/jpeg", 1024, HASH);
    }

    private MediaBackfillPreparationService service(boolean enabled, Map<String, Object> beans) {
        return new MediaBackfillPreparationService(new MediaBackfillProperties(enabled),
                new StaticListableBeanFactory(beans).getBeanProvider(MediaBackfillStorage.class),
                IDENTITY_FACTORY, reservations, transactions, CLOCK);
    }

    private void assertUnavailable(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", MediaErrorCode.PIPELINE_UNAVAILABLE);
    }

    private void assertSourceFailure(Runnable action, MediaBackfillStorageException.Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(MediaBackfillSourceException.class, exception -> {
            assertThat(exception.getReason().name()).isEqualTo(reason.name());
            assertThat(exception.getMessage()).isEqualTo("media backfill source: " + reason.name());
            assertThat(exception.getCause()).isNull();
        });
    }
}
