package org.sopt.hashi.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import org.sopt.hashi.media.MediaAssetPurpose;
import org.sopt.hashi.media.MediaAssetUse;
import org.sopt.hashi.media.MediaImage;
import org.sopt.hashi.media.MediaImageRequest;
import org.sopt.hashi.media.MediaImageRole;
import org.sopt.hashi.media.MediaImageStatus;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.media.code.MediaErrorCode;
import org.sopt.hashi.media.domain.ImageAsset;
import org.sopt.hashi.media.domain.ImageAssetRepository;
import org.sopt.hashi.media.domain.ImageFormat;
import org.sopt.hashi.media.domain.ImageProcessingStatus;
import org.sopt.hashi.media.domain.ImageRole;
import org.sopt.hashi.media.domain.MediaPurpose;
import org.sopt.hashi.media.internal.event.MediaProcessingRequestedEvent;
import org.sopt.hashi.media.internal.queue.MediaRenditionResult;
import org.sopt.hashi.media.internal.queue.MediaTransformRequest;
import org.sopt.hashi.media.internal.queue.MediaTransformRequestPublisher;
import org.sopt.hashi.media.internal.queue.MediaTransformSucceededResult;
import org.sopt.hashi.media.internal.queue.MediaVerifiedSource;
import org.sopt.hashi.media.internal.spec.MediaSpecRegistry;
import org.sopt.hashi.media.internal.storage.MediaOriginalStorage;
import org.sopt.hashi.media.internal.storage.OriginalObjectMetadata;
import org.sopt.hashi.shared.error.BusinessException;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ApplicationModuleTest
@Import(MediaCardNewsModuleIntegrationTest.Infrastructure.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "hashi.media.recovery.enabled=false",
        "jwt.secret=test-secret-key-must-be-at-least-32-bytes-long",
        "kakao.client-id=test-client-id",
        "kakao.redirect-uri=https://app.hashi.test/callback",
        "hashi.storage.cloudfront-domain=https://cdn.hashi.test"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MediaCardNewsModuleIntegrationTest {

    private static final CurrentActor ADMIN = new CurrentActor(ActorType.ADMIN, 7L);
    private static final String CHECKSUM = "47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=";

    @Container
    @ServiceConnection
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("hashi_card_news")
            .withUsername("hashi")
            .withPassword("hashi");

    @Autowired
    private MediaAssetTransactionService transactionService;
    @Autowired
    private MediaTransformResultService resultService;
    @Autowired
    private ImageAssetRepository assets;
    @Autowired
    private MediaSpecRegistry specs;
    @Autowired
    private MediaPort mediaPort;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    @Qualifier("japanClock")
    private Clock clock;

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
    void 격리된_DB에_v1_발급_설정을_준비한다() {
        jdbc.update("DELETE FROM event_publication");
        jdbc.update("DELETE FROM image_rendition");
        jdbc.update("DELETE FROM image_asset");
        activateSpec(1);
        when(actors.currentActor()).thenReturn(ADMIN);
        when(fileStorage.resolveFileUrl(anyString())).thenAnswer(invocation ->
                "https://cdn.hashi.test/" + invocation.getArgument(0, String.class));
    }

    @AfterEach
    void 이벤트_처리_완료를_확인하고_외부_업로드를_실행하지_않는다() {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM event_publication", Integer.class)).isZero());
        verifyNoInteractions(originalStorage);
        verify(fileStorage, never()).createPresignedUploadUrl(anyString(), anyString(), anyLong());
    }

    @Test
    void 카드뉴스는_v2_활성화_후에만_생성하고_변환을_요청한다(Scenario scenario) {
        UUID assetId = UUID.randomUUID();
        long bytes = 10L * 1024 * 1024;
        List<PreparedMediaAsset> uploads = uploads(assetId, bytes);

        assertThatThrownBy(() -> transactionService.createAssets(
                ADMIN, MediaPurpose.MAGAZINE_CARD_NEWS, uploads))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", MediaErrorCode.PIPELINE_UNAVAILABLE);
        assertThat(assets.findByPublicId(assetId)).isEmpty();

        activateSpec(2);
        transactionService.createAssets(ADMIN, MediaPurpose.MAGAZINE_CARD_NEWS, uploads);
        completeAndVerifyRequest(scenario, assetId, bytes);

        ImageAsset asset = assets.findByPublicId(assetId).orElseThrow();
        assertThat(asset.getPurpose()).isEqualTo(MediaPurpose.MAGAZINE_CARD_NEWS);
        assertThat(asset.getLastIssuedSpecVersion()).isEqualTo(2);
        assertThat(asset.getProcessingStatus()).isEqualTo(ImageProcessingStatus.PROCESSING);
    }

    @Test
    void 가로폭이_같은_긴_카드뉴스_결과는_하나만_저장하고_READY로_조회한다(Scenario scenario) {
        activateSpec(2);
        UUID assetId = UUID.randomUUID();
        long bytes = 1024;
        transactionService.createAssets(ADMIN, MediaPurpose.MAGAZINE_CARD_NEWS, uploads(assetId, bytes));
        MediaTransformRequest request = completeAndVerifyRequest(scenario, assetId, bytes);
        String renditionKey = "media/renditions/%s/v2/magazine-card-news/1.webp".formatted(assetId);
        MediaTransformSucceededResult result = new MediaTransformSucceededResult(
                request.contractVersion(), request.jobId(), assetId, 2, request.specDigest(),
                request.sourceVersionId(), request.sourceETag(),
                new MediaVerifiedSource("image/png", bytes, 1, 1200, CHECKSUM),
                List.of(new MediaRenditionResult(
                        ImageRole.MAGAZINE_CARD_NEWS, ImageFormat.WEBP, 1, 1200, 100, renditionKey)));

        assertThat(resultService.apply(result).disposition()).isEqualTo(MediaTransformResultDisposition.APPLIED);

        ImageAsset asset = assets.findByPublicId(assetId).orElseThrow();
        assertThat(asset.getProcessingStatus()).isEqualTo(ImageProcessingStatus.READY);
        assertThat(asset.getActiveSpecVersion()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM image_rendition WHERE image_asset_id = ?", Integer.class, asset.getId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForMap(
                "SELECT width, height, object_key FROM image_rendition WHERE image_asset_id = ?", asset.getId()))
                .containsEntry("width", 1)
                .containsEntry("height", 1200)
                .containsEntry("object_key", renditionKey);
        transactions.executeWithoutResult(status -> mediaPort.reconcileBindings(
                List.of(new MediaAssetUse(assetId, MediaAssetPurpose.MAGAZINE_CARD_NEWS)), List.of()));
        MediaImageRequest imageRequest = new MediaImageRequest(assetId, MediaImageRole.MAGAZINE_CARD_NEWS);
        MediaImage image = mediaPort.findImages(List.of(imageRequest)).get(imageRequest);
        assertThat(image.status()).isEqualTo(MediaImageStatus.READY);
        assertThat(image.defaultSource().width()).isEqualTo(1);
        assertThat(image.defaultSource().height()).isEqualTo(1200);
        assertThat(image.defaultSource().url()).isEqualTo("https://cdn.hashi.test/" + renditionKey);
    }

    private MediaTransformRequest completeAndVerifyRequest(Scenario scenario, UUID assetId, long bytes) {
        OriginalObjectMetadata source = new OriginalObjectMetadata(
                objectKey(assetId), "version-1", "\"etag-1\"", "image/png", bytes);
        scenario.stimulate(() -> transactionService.completeAssets(
                        ADMIN, List.of(assetId), Map.of(assetId, source)))
                .andWaitForEventOfType(MediaProcessingRequestedEvent.class)
                .matching(event -> event.assetId().equals(assetId))
                .toArriveAndVerify(event -> assertThat(event.jobId())
                        .isEqualTo(MediaProcessingJobId.from(assetId, "version-1", 2)));
        ArgumentCaptor<MediaTransformRequest> request = ArgumentCaptor.forClass(MediaTransformRequest.class);
        verify(publisher, timeout(10_000)).publish(request.capture());
        assertThat(request.getValue().assetId()).isEqualTo(assetId);
        assertThat(request.getValue().purpose()).isEqualTo(MediaPurpose.MAGAZINE_CARD_NEWS);
        assertThat(request.getValue().specVersion()).isEqualTo(2);
        assertThat(request.getValue().specDigest()).isEqualTo(specs.find(2).orElseThrow().digest());
        assertThat(request.getValue().originalKey()).isEqualTo(objectKey(assetId));
        assertThat(request.getValue().declaredByteSize()).isEqualTo(bytes);
        return request.getValue();
    }

    private List<PreparedMediaAsset> uploads(UUID assetId, long bytes) {
        return List.of(new PreparedMediaAsset(
                assetId, objectKey(assetId), "image/png", bytes, LocalDateTime.now(clock).plusMinutes(5)));
    }

    private void activateSpec(int version) {
        jdbc.update("""
                UPDATE media_pipeline_config
                SET current_spec_version = ?, current_spec_digest = ?, issuance_enabled = TRUE,
                    lock_version = lock_version + 1, updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = 1
                """, version, specs.find(version).orElseThrow().digest());
    }

    private String objectKey(UUID assetId) {
        return "media/originals/%s/original".formatted(assetId);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableJpaAuditing(dateTimeProviderRef = "cardNewsTestDateTimeProvider")
    static class Infrastructure {

        @Bean("japanClock")
        Clock japanClock() {
            return Clock.fixed(Instant.parse("2026-09-27T00:00:00Z"), ZoneId.of("Asia/Tokyo"));
        }

        @Bean
        DateTimeProvider cardNewsTestDateTimeProvider(@Qualifier("japanClock") Clock clock) {
            return () -> Optional.of(LocalDateTime.now(clock));
        }
    }
}
