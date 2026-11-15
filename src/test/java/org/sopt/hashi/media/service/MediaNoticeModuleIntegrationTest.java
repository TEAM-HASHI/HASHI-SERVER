package org.sopt.hashi.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.sopt.hashi.auth.ActorType;
import org.sopt.hashi.auth.CurrentActor;
import org.sopt.hashi.auth.CurrentActorProvider;
import org.sopt.hashi.media.domain.ImageAsset;
import org.sopt.hashi.media.domain.ImageAssetRepository;
import org.sopt.hashi.media.domain.ImageFormat;
import org.sopt.hashi.media.domain.ImageProcessingStatus;
import org.sopt.hashi.media.domain.ImageRole;
import org.sopt.hashi.media.domain.MediaPurpose;
import org.sopt.hashi.media.dto.CompleteMediaAssetsRequest;
import org.sopt.hashi.media.dto.CreateMediaAssetsRequest;
import org.sopt.hashi.media.dto.MediaUploadResponse;
import org.sopt.hashi.media.internal.event.MediaProcessingRequestedEvent;
import org.sopt.hashi.media.internal.queue.MediaRenditionResult;
import org.sopt.hashi.media.internal.queue.MediaTransformRequest;
import org.sopt.hashi.media.internal.queue.MediaTransformRequestPublisher;
import org.sopt.hashi.media.internal.queue.MediaTransformSucceededResult;
import org.sopt.hashi.media.internal.queue.MediaVerifiedSource;
import org.sopt.hashi.media.internal.spec.MediaSpecRegistry;
import org.sopt.hashi.media.internal.storage.MediaOriginalStorage;
import org.sopt.hashi.media.internal.storage.OriginalObjectMetadata;
import org.sopt.hashi.media.internal.storage.PresignedOriginalUpload;
import org.sopt.hashi.shared.storage.FileStorage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ApplicationModuleTest
@Import(MediaNoticeModuleIntegrationTest.Infrastructure.class)
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "hashi.media.recovery.enabled=false",
        "hashi.media.queue.enabled=false",
        "jwt.secret=test-secret-key-must-be-at-least-32-bytes-long",
        "kakao.client-id=test-client-id",
        "kakao.redirect-uri=https://app.hashi.test/callback",
        "hashi.storage.cloudfront-domain=https://cdn.hashi.test"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MediaNoticeModuleIntegrationTest {

    private static final CurrentActor ADMIN = new CurrentActor(ActorType.ADMIN, 7L);
    private static final String CHECKSUM = "47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=";

    @Container
    @ServiceConnection
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("hashi_notice_media")
            .withUsername("hashi")
            .withPassword("hashi");

    @Autowired
    private MediaAssetService assetService;
    @Autowired
    private MediaTransformResultService resultService;
    @Autowired
    private ImageAssetRepository assets;
    @Autowired
    private MediaSpecRegistry specs;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private CurrentActorProvider actors;
    @MockitoBean
    private FileStorage fileStorage;
    @MockitoBean
    private MediaOriginalStorage originalStorage;
    @MockitoBean
    private MediaTransformRequestPublisher publisher;
    @MockitoBean
    private RedisTemplate<String, Object> redisTemplate;

    @BeforeEach
    void 격리된_DB에_공지_발급용_v3_설정을_준비한다() {
        jdbc.update("DELETE FROM event_publication");
        jdbc.update("DELETE FROM image_rendition");
        jdbc.update("DELETE FROM image_asset");
        jdbc.update("""
                UPDATE media_pipeline_config
                SET current_spec_version = 3, current_spec_digest = ?, issuance_enabled = TRUE,
                    lock_version = lock_version + 1, updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = 1
                """, specs.find(3).orElseThrow().digest());
        when(actors.currentActor()).thenReturn(ADMIN);
        when(originalStorage.createPresignedUpload(anyString(), anyString(), anyLong()))
                .thenAnswer(invocation -> new PresignedOriginalUpload(
                        "https://uploads.hashi.test/" + invocation.getArgument(0, String.class),
                        Map.of("Content-Type", invocation.getArgument(1, String.class)),
                        invocation.getArgument(2, Long.class), 300, "PUT"));
    }

    @AfterEach
    void 변환_요청_이벤트_처리가_완료된다() {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM event_publication", Integer.class)).isZero());
    }

    @Test
    void 공지_asset을_발급하고_변환_결과를_MySQL에_저장한다(Scenario scenario) {
        long bytes = 1024;
        MediaUploadResponse upload = assetService.createAssets(new CreateMediaAssetsRequest(
                MediaPurpose.NOTICE,
                List.of(new CreateMediaAssetsRequest.FileRequest("image/png", bytes))))
                .uploads().getFirst();
        UUID assetId = upload.assetId();
        String originalKey = "media/originals/%s/original".formatted(assetId);

        assertThat(upload.status()).isEqualTo(ImageProcessingStatus.PENDING_UPLOAD);
        ImageAsset pending = assets.findByPublicId(assetId).orElseThrow();
        assertThat(pending.getPurpose()).isEqualTo(MediaPurpose.NOTICE);
        assertThat(pending.getProcessingStatus()).isEqualTo(ImageProcessingStatus.PENDING_UPLOAD);
        verify(originalStorage).createPresignedUpload(originalKey, "image/png", bytes);
        when(originalStorage.findObjectMetadata(originalKey)).thenReturn(Optional.of(
                new OriginalObjectMetadata(originalKey, "version-1", "\"etag-1\"", "image/png", bytes)));

        scenario.stimulate(() -> assetService.completeAssets(new CompleteMediaAssetsRequest(List.of(assetId))))
                .andWaitForEventOfType(MediaProcessingRequestedEvent.class)
                .matching(event -> event.assetId().equals(assetId))
                .toArriveAndVerify(event -> assertThat(event.jobId())
                        .isEqualTo(MediaProcessingJobId.from(assetId, "version-1", 3)));
        ArgumentCaptor<MediaTransformRequest> captured = ArgumentCaptor.forClass(MediaTransformRequest.class);
        verify(publisher, timeout(10_000)).publish(captured.capture());
        MediaTransformRequest request = captured.getValue();
        assertThat(request.assetId()).isEqualTo(assetId);
        assertThat(request.purpose()).isEqualTo(MediaPurpose.NOTICE);
        assertThat(request.specVersion()).isEqualTo(3);
        assertThat(request.specDigest()).isEqualTo(specs.find(3).orElseThrow().digest());
        assertThat(request.originalKey()).isEqualTo(originalKey);
        assertThat(request.sourceVersionId()).isEqualTo("version-1");
        assertThat(request.sourceETag()).isEqualTo("\"etag-1\"");
        assertThat(request.declaredByteSize()).isEqualTo(bytes);
        assertThat(assets.findByPublicId(assetId).orElseThrow().getProcessingStatus())
                .isEqualTo(ImageProcessingStatus.PROCESSING);

        String renditionKey = "media/renditions/%s/v3/notice-detail/320.webp".formatted(assetId);
        MediaTransformSucceededResult result = new MediaTransformSucceededResult(
                request.contractVersion(), request.jobId(), assetId, 3, request.specDigest(),
                request.sourceVersionId(), request.sourceETag(),
                new MediaVerifiedSource("image/png", bytes, 320, 240, CHECKSUM),
                List.of(new MediaRenditionResult(
                        ImageRole.NOTICE_DETAIL, ImageFormat.WEBP, 320, 240, 100, renditionKey)));

        assertThat(resultService.apply(result).disposition()).isEqualTo(MediaTransformResultDisposition.APPLIED);

        ImageAsset ready = assets.findByPublicId(assetId).orElseThrow();
        assertThat(ready.getPurpose()).isEqualTo(MediaPurpose.NOTICE);
        assertThat(ready.getProcessingStatus()).isEqualTo(ImageProcessingStatus.READY);
        assertThat(ready.getActiveSpecVersion()).isEqualTo(3);
        assertThat(jdbc.queryForMap("""
                SELECT role, spec_version, width, height, object_key
                FROM image_rendition WHERE image_asset_id = ?
                """, ready.getId()))
                .containsEntry("role", "NOTICE_DETAIL")
                .containsEntry("spec_version", 3)
                .containsEntry("width", 320)
                .containsEntry("height", 240)
                .containsEntry("object_key", renditionKey);
        assertThat(assetService.getAssetStatuses(List.of(assetId)).assets()).singleElement()
                .satisfies(status -> {
                    assertThat(status.assetId()).isEqualTo(assetId);
                    assertThat(status.status()).isEqualTo(ImageProcessingStatus.READY);
                });
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableJpaAuditing(dateTimeProviderRef = "noticeMediaTestDateTimeProvider")
    static class Infrastructure {

        @Bean("japanClock")
        Clock japanClock() {
            return Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"), ZoneId.of("Asia/Tokyo"));
        }

        @Bean
        DateTimeProvider noticeMediaTestDateTimeProvider(@Qualifier("japanClock") Clock clock) {
            return () -> Optional.of(LocalDateTime.now(clock));
        }
    }
}
