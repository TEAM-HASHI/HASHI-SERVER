package org.sopt.hashi.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.hashi.auth.ActorType;
import org.sopt.hashi.auth.CurrentActor;
import org.sopt.hashi.auth.CurrentActorProvider;
import org.sopt.hashi.media.code.MediaErrorCode;
import org.sopt.hashi.media.domain.ImageProcessingStatus;
import org.sopt.hashi.media.domain.MediaCleanupStatus;
import org.sopt.hashi.media.domain.MediaPurpose;
import org.sopt.hashi.media.dto.CompleteMediaAssetsRequest;
import org.sopt.hashi.media.dto.CreateMediaAssetsRequest;
import org.sopt.hashi.media.dto.CreateMediaAssetsResponse;
import org.sopt.hashi.media.dto.MediaAssetStatusesResponse;
import org.sopt.hashi.media.internal.storage.MediaOriginalStorage;
import org.sopt.hashi.media.internal.storage.MediaOriginalStorageProperties;
import org.sopt.hashi.media.internal.storage.OriginalObjectMetadata;
import org.sopt.hashi.media.internal.storage.PresignedOriginalUpload;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.util.unit.DataSize;

@ExtendWith(MockitoExtension.class)
class MediaAssetServiceTest {

    private static final CurrentActor USER = new CurrentActor(ActorType.USER, 1L);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-27T00:00:00Z"),
            ZoneId.of("Asia/Tokyo")
    );

    @Mock
    private CurrentActorProvider currentActorProvider;

    @Mock
    private MediaAssetTransactionService transactionService;

    @Mock
    private MediaOriginalStorage originalStorage;

    private MediaAssetService service;

    @BeforeEach
    void setUp() {
        MediaOriginalStorageProperties properties = new MediaOriginalStorageProperties(
                "ap-northeast-2",
                "hashi-test-originals",
                Duration.ofMinutes(5),
                DataSize.ofMegabytes(5),
                10
        );
        service = new MediaAssetService(
                currentActorProvider,
                new MediaPurposeAccessPolicy(),
                transactionService,
                originalStorage,
                properties,
                CLOCK
        );
    }

    @Test
    void USER는_REVIEW_asset과_서명된_업로드_정보를_발급받는다() {
        given(currentActorProvider.currentActor()).willReturn(USER);
        given(originalStorage.createPresignedUpload(
                org.mockito.ArgumentMatchers.startsWith("media/originals/"),
                eq("image/jpeg"),
                eq(1024L)
        )).willReturn(new PresignedOriginalUpload(
                "https://s3.example.com/presigned",
                Map.of("Content-Type", "image/jpeg", "If-None-Match", "*"),
                1024L,
                300,
                "PUT"
        ));

        CreateMediaAssetsResponse response = service.createAssets(createReviewRequest());

        assertThat(response.uploads()).singleElement().satisfies(upload -> {
            assertThat(upload.status()).isEqualTo(ImageProcessingStatus.PENDING_UPLOAD);
            assertThat(upload.uploadUrl()).isEqualTo("https://s3.example.com/presigned");
            assertThat(upload.requiredHeaders())
                    .containsEntry("Content-Type", "image/jpeg")
                    .containsEntry("If-None-Match", "*");
            assertThat(upload.expectedContentLength()).isEqualTo(1024L);
        });
        verify(transactionService).assertIssuanceAvailable();
        verify(transactionService).createAssets(eq(USER), eq(MediaPurpose.REVIEW), anyList());
    }

    @Test
    void 권한이_없는_purpose는_pipeline이나_storage를_호출하기_전에_거부한다() {
        given(currentActorProvider.currentActor()).willReturn(USER);
        CreateMediaAssetsRequest request = new CreateMediaAssetsRequest(
                MediaPurpose.RESTAURANT,
                List.of(new CreateMediaAssetsRequest.FileRequest("image/jpeg", 1024L))
        );

        assertThatThrownBy(() -> service.createAssets(request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", MediaErrorCode.PURPOSE_FORBIDDEN);
        verifyNoInteractions(transactionService, originalStorage);
    }

    @Test
    void 지원하지_않는_MIME은_storage_호출_전에_거부한다() {
        given(currentActorProvider.currentActor()).willReturn(USER);
        CreateMediaAssetsRequest request = new CreateMediaAssetsRequest(
                MediaPurpose.REVIEW,
                List.of(new CreateMediaAssetsRequest.FileRequest("image/gif", 1024L))
        );

        assertThatThrownBy(() -> service.createAssets(request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", MediaErrorCode.UNSUPPORTED_FILE_TYPE);
        verifyNoInteractions(transactionService, originalStorage);
    }

    @Test
    void complete에_중복_assetId가_있으면_조회나_HEAD를_하지_않는다() {
        UUID assetId = UUID.randomUUID();
        CompleteMediaAssetsRequest request = new CompleteMediaAssetsRequest(List.of(assetId, assetId));

        assertThatThrownBy(() -> service.completeAssets(request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", MediaErrorCode.DUPLICATE_ASSET);
        verifyNoInteractions(currentActorProvider, transactionService, originalStorage);
    }

    @Test
    void PENDING_UPLOAD_원본이_하나라도_없으면_어떤_asset도_전이하지_않는다() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        given(currentActorProvider.currentActor()).willReturn(USER);
        given(transactionService.loadOwnedAssets(USER, List.of(firstId, secondId)))
                .willReturn(List.of(pending(firstId), pending(secondId)));
        given(originalStorage.findObjectMetadata(objectKey(firstId)))
                .willReturn(Optional.of(metadata(firstId, 1024L, "image/jpeg")));
        given(originalStorage.findObjectMetadata(objectKey(secondId))).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.completeAssets(
                new CompleteMediaAssetsRequest(List.of(firstId, secondId))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", MediaErrorCode.UPLOAD_NOT_FOUND);
        verify(transactionService, never()).completeAssets(eq(USER), anyList(), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void HEAD_크기가_발급값과_다르면_전이하지_않는다() {
        UUID assetId = UUID.randomUUID();
        given(currentActorProvider.currentActor()).willReturn(USER);
        given(transactionService.loadOwnedAssets(USER, List.of(assetId)))
                .willReturn(List.of(pending(assetId)));
        given(originalStorage.findObjectMetadata(objectKey(assetId)))
                .willReturn(Optional.of(metadata(assetId, 2048L, "image/jpeg")));

        assertThatThrownBy(() -> service.completeAssets(
                new CompleteMediaAssetsRequest(List.of(assetId))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", MediaErrorCode.UPLOAD_METADATA_MISMATCH);
        verify(transactionService, never()).completeAssets(eq(USER), anyList(), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void PROCESSING_complete_재호출은_HEAD없이_현재_상태를_반환한다() {
        UUID assetId = UUID.randomUUID();
        OwnedAssetSnapshot processing = new OwnedAssetSnapshot(
                assetId,
                objectKey(assetId),
                "image/jpeg",
                1024L,
                LocalDateTime.now(CLOCK).plusMinutes(5),
                ImageProcessingStatus.PROCESSING,
                MediaCleanupStatus.ACTIVE
        );
        given(currentActorProvider.currentActor()).willReturn(USER);
        given(transactionService.loadOwnedAssets(USER, List.of(assetId)))
                .willReturn(List.of(processing));
        given(transactionService.completeAssets(USER, List.of(assetId), Map.of()))
                .willReturn(List.of(processing));

        MediaAssetStatusesResponse response = service.completeAssets(
                new CompleteMediaAssetsRequest(List.of(assetId)));

        assertThat(response.assets()).singleElement().satisfies(status ->
                assertThat(status.status()).isEqualTo(ImageProcessingStatus.PROCESSING));
        verifyNoInteractions(originalStorage);
    }

    private CreateMediaAssetsRequest createReviewRequest() {
        return new CreateMediaAssetsRequest(
                MediaPurpose.REVIEW,
                List.of(new CreateMediaAssetsRequest.FileRequest("image/jpeg", 1024L))
        );
    }

    private OwnedAssetSnapshot pending(UUID assetId) {
        return new OwnedAssetSnapshot(
                assetId,
                objectKey(assetId),
                "image/jpeg",
                1024L,
                LocalDateTime.now(CLOCK).plusMinutes(5),
                ImageProcessingStatus.PENDING_UPLOAD,
                MediaCleanupStatus.ACTIVE
        );
    }

    private OriginalObjectMetadata metadata(UUID assetId, long bytes, String contentType) {
        return new OriginalObjectMetadata(
                objectKey(assetId),
                "version-1",
                "\"etag-1\"",
                contentType,
                bytes
        );
    }

    private String objectKey(UUID assetId) {
        return "media/originals/%s/original".formatted(assetId);
    }
}
