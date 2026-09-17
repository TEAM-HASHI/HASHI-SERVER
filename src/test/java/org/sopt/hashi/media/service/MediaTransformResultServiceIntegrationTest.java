package org.sopt.hashi.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sqs.listener.acknowledgement.Acknowledgement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.media.domain.ImageAsset;
import org.sopt.hashi.media.domain.ImageAssetRepository;
import org.sopt.hashi.media.domain.ImageFormat;
import org.sopt.hashi.media.domain.ImageProcessingStatus;
import org.sopt.hashi.media.domain.ImageRole;
import org.sopt.hashi.media.domain.MediaCreationOrigin;
import org.sopt.hashi.media.domain.MediaOwnerType;
import org.sopt.hashi.media.domain.MediaPurpose;
import org.sopt.hashi.media.internal.metrics.MediaPipelineMetrics;
import org.sopt.hashi.media.internal.queue.MediaRenditionResult;
import org.sopt.hashi.media.internal.queue.MediaTransformContractException;
import org.sopt.hashi.media.internal.queue.MediaTransformFailedResult;
import org.sopt.hashi.media.internal.queue.MediaTransformFailureCode;
import org.sopt.hashi.media.internal.queue.MediaTransformResultListener;
import org.sopt.hashi.media.internal.queue.MediaTransformResultParser;
import org.sopt.hashi.media.internal.queue.MediaTransformSucceededResult;
import org.sopt.hashi.media.internal.queue.MediaVerifiedSource;
import org.sopt.hashi.media.internal.recovery.MediaCleanupCandidate;
import org.sopt.hashi.media.internal.recovery.MediaCleanupCandidateCursor;
import org.sopt.hashi.media.internal.recovery.MediaCleanupCandidateReader;
import org.sopt.hashi.media.internal.recovery.MediaProcessingRecoveryCandidate;
import org.sopt.hashi.media.internal.recovery.MediaProcessingRecoveryCursor;
import org.sopt.hashi.media.internal.recovery.MediaProcessingRecoveryReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "jwt.secret=test-secret-key-must-be-at-least-32-bytes-long",
        "kakao.client-id=test-client-id",
        "kakao.redirect-uri=https://app.hashi.test/callback",
        "hashi.storage.cloudfront-domain=https://cdn.hashi.test"
})
class MediaTransformResultServiceIntegrationTest {

    private static final String SPEC_DIGEST =
            "1b5759a9285732133699114e21101b3b9b43b5cd8e208bf1246d059f4293634f";
    private static final String OTHER_DIGEST =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String SOURCE_CHECKSUM =
            "47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=";

    @Container
    @ServiceConnection
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("hashi")
            .withUsername("hashi")
            .withPassword("hashi");

    @Autowired
    private MediaTransformResultService resultService;

    @Autowired
    private ImageAssetRepository imageAssetRepository;

    @Autowired
    private MediaProcessingRecoveryReader recoveryReader;

    @Autowired
    private MediaCleanupCandidateReader cleanupCandidateReader;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MediaTransformResultParser resultParser;

    @Autowired
    private MediaPipelineMetrics metrics;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM event_publication");
        jdbcTemplate.update("DELETE FROM image_rendition");
        jdbcTemplate.update("DELETE FROM image_asset");
    }

    @Test
    void listener는_DB_commit이_다른_connection에_보인_뒤에_ACK한다() throws Exception {
        ProcessingAsset processing = createProcessingAsset(1, SPEC_DIGEST);
        AtomicBoolean committedWhenAcknowledged = new AtomicBoolean();
        RecordingAcknowledgement acknowledgement = new RecordingAcknowledgement(() ->
                committedWhenAcknowledged.set(databaseStatus(processing.assetId())
                        == ImageProcessingStatus.FAILED));
        MediaTransformResultListener listener = new MediaTransformResultListener(
                resultParser, resultService, metrics);

        listener.consume(failedBody(processing), acknowledgement);

        assertThat(acknowledgement.acknowledged()).isTrue();
        assertThat(committedWhenAcknowledged).isTrue();
    }

    @Test
    void listener는_commit_시점_DB_오류가_나면_rollback하고_ACK하지_않는다() throws Exception {
        ProcessingAsset processing = createProcessingAsset(1, SPEC_DIGEST);
        ImageAsset otherAsset = createPendingAsset();
        MediaRenditionResult conflicting = successResult(processing).renditions().getFirst();
        jdbcTemplate.update("""
                INSERT INTO image_rendition (
                    image_asset_id, role, spec_version, format, mime_type,
                    width, height, bytes, object_key, created_at, updated_at
                ) VALUES (?, ?, ?, 'WEBP', 'image/webp', ?, ?, ?, ?, NOW(6), NOW(6))
                """,
                otherAsset.getId(),
                conflicting.role().name(),
                processing.specVersion(),
                conflicting.width(),
                conflicting.height(),
                conflicting.byteSize(),
                conflicting.objectKey());
        RecordingAcknowledgement acknowledgement = new RecordingAcknowledgement(() -> { });
        MediaTransformResultListener listener = new MediaTransformResultListener(
                resultParser, resultService, metrics);

        assertThatThrownBy(() -> listener.consume(succeededBody(processing), acknowledgement))
                .isInstanceOf(RuntimeException.class);

        assertThat(acknowledgement.acknowledged()).isFalse();
        assertThat(databaseStatus(processing.assetId()))
                .isEqualTo(ImageProcessingStatus.PROCESSING);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM image_rendition WHERE image_asset_id = ?",
                Integer.class,
                imageAssetRepository.findByPublicId(processing.assetId()).orElseThrow().getId()))
                .isZero();
    }

    @Test
    void 성공_결과는_검증된_source와_정확한_rendition을_원자적으로_READY에_반영한다() {
        ProcessingAsset processing = createProcessingAsset(1, SPEC_DIGEST);

        MediaTransformResultApplication application =
                resultService.apply(successResult(processing));

        assertThat(application.disposition()).isEqualTo(MediaTransformResultDisposition.APPLIED);
        assertThat(application.processingDuration().isNegative()).isFalse();
        ImageAsset saved = find(processing.assetId());
        assertThat(saved.getProcessingStatus()).isEqualTo(ImageProcessingStatus.READY);
        assertThat(saved.getActiveSpecVersion()).isEqualTo(1);
        assertThat(saved.getActiveSpecDigest()).isEqualTo(SPEC_DIGEST);
        assertThat(saved.getCurrentJobId()).isNull();
        assertThat(saved.getActualContentType()).isEqualTo("image/jpeg");
        assertThat(saved.getActualBytes()).isEqualTo(1_048_576L);
        assertThat(saved.getSourceWidth()).isEqualTo(3024);
        assertThat(saved.getSourceHeight()).isEqualTo(4032);
        assertThat(saved.getSourceChecksumSha256()).isEqualTo(SOURCE_CHECKSUM);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM image_rendition", Integer.class)).isEqualTo(6);
    }

    @Test
    void 같은_성공_결과가_다시_오면_STALE로_ACK할_수_있고_중복_저장하지_않는다() {
        ProcessingAsset processing = createProcessingAsset(1, SPEC_DIGEST);
        MediaTransformSucceededResult result = successResult(processing);
        resultService.apply(result);

        MediaTransformResultApplication application = resultService.apply(result);

        assertThat(application.disposition()).isEqualTo(MediaTransformResultDisposition.STALE);
        assertThat(application.processingDuration()).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM image_rendition", Integer.class)).isEqualTo(6);
    }

    @Test
    void 성공_뒤_늦게_도착한_FAILED는_READY를_낮추지_않는다() {
        ProcessingAsset processing = createProcessingAsset(1, SPEC_DIGEST);
        resultService.apply(successResult(processing));

        MediaTransformResultApplication application =
                resultService.apply(failedResult(processing));

        assertThat(application.disposition()).isEqualTo(MediaTransformResultDisposition.STALE);
        assertThat(find(processing.assetId()).getProcessingStatus())
                .isEqualTo(ImageProcessingStatus.READY);
    }

    @Test
    void 현재_초기_job의_영구_실패는_FAILED로_반영한다() {
        ProcessingAsset processing = createProcessingAsset(1, SPEC_DIGEST);

        MediaTransformResultApplication application =
                resultService.apply(failedResult(processing));

        assertThat(application.disposition()).isEqualTo(MediaTransformResultDisposition.APPLIED);
        ImageAsset saved = find(processing.assetId());
        assertThat(saved.getProcessingStatus()).isEqualTo(ImageProcessingStatus.FAILED);
        assertThat(saved.getLastFailureSpecVersion()).isEqualTo(1);
        assertThat(saved.getLastFailureCode()).isEqualTo("INVALID_IMAGE_DATA");
        assertThat(saved.getCurrentJobId()).isNull();
    }

    @Test
    void 교체된_job의_알_수_없는_spec은_manifest를_조회하지_않고_STALE로_처리한다() {
        ProcessingAsset processing = createProcessingAsset(1, SPEC_DIGEST);
        ProcessingAsset stale = new ProcessingAsset(
                processing.assetId(),
                UUID.randomUUID(),
                processing.sourceVersionId(),
                processing.sourceEtag(),
                999,
                OTHER_DIGEST
        );

        MediaTransformResultApplication application =
                resultService.apply(failedResult(stale));

        assertThat(application.disposition()).isEqualTo(MediaTransformResultDisposition.STALE);
        assertThat(find(processing.assetId()).getProcessingStatus())
                .isEqualTo(ImageProcessingStatus.PROCESSING);
    }

    @Test
    void 현재_job이지만_target_spec이_다른_결과는_재시도를_위해_예외로_남긴다() {
        ProcessingAsset processing = createProcessingAsset(1, SPEC_DIGEST);
        ProcessingAsset mismatched = new ProcessingAsset(
                processing.assetId(),
                processing.jobId(),
                processing.sourceVersionId(),
                processing.sourceEtag(),
                2,
                OTHER_DIGEST
        );

        assertThatThrownBy(() -> resultService.apply(failedResult(mismatched)))
                .isInstanceOf(MediaTransformContractException.class)
                .hasMessage("media result target spec differs from the current job");
        assertThat(find(processing.assetId()).getProcessingStatus())
                .isEqualTo(ImageProcessingStatus.PROCESSING);
    }

    @Test
    void 현재_job의_manifest가_서버에_없으면_FAILED로_오인하지_않고_예외로_남긴다() {
        ProcessingAsset processing = createProcessingAsset(999, OTHER_DIGEST);

        assertThatThrownBy(() -> resultService.apply(failedResult(processing)))
                .isInstanceOf(MediaTransformContractException.class)
                .hasMessage("media result references an unknown spec version");
        assertThat(find(processing.assetId()).getProcessingStatus())
                .isEqualTo(ImageProcessingStatus.PROCESSING);
    }

    @Test
    void 현재_job의_digest가_패키징된_manifest와_다르면_예외로_남긴다() {
        ProcessingAsset processing = createProcessingAsset(1, OTHER_DIGEST);

        assertThatThrownBy(() -> resultService.apply(failedResult(processing)))
                .isInstanceOf(MediaTransformContractException.class)
                .hasMessage("media result spec digest differs from the packaged manifest");
        assertThat(find(processing.assetId()).getProcessingStatus())
                .isEqualTo(ImageProcessingStatus.PROCESSING);
    }

    @Test
    void 일부가_빠진_성공_결과는_저장하지_않고_PROCESSING을_유지한다() {
        ProcessingAsset processing = createProcessingAsset(1, SPEC_DIGEST);
        MediaTransformSucceededResult complete = successResult(processing);
        MediaTransformSucceededResult incomplete = new MediaTransformSucceededResult(
                complete.contractVersion(),
                complete.jobId(),
                complete.assetId(),
                complete.specVersion(),
                complete.specDigest(),
                complete.sourceVersionId(),
                complete.sourceETag(),
                complete.verifiedSource(),
                complete.renditions().subList(0, 1)
        );

        assertThatThrownBy(() -> resultService.apply(incomplete))
                .isInstanceOf(MediaTransformContractException.class)
                .hasMessage("media result renditions differ from the packaged manifest");
        assertThat(find(processing.assetId()).getProcessingStatus())
                .isEqualTo(ImageProcessingStatus.PROCESSING);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM image_rendition", Integer.class)).isZero();
    }

    @Test
    void 결정적_S3_key와_다른_성공_결과는_저장하지_않는다() {
        ProcessingAsset processing = createProcessingAsset(1, SPEC_DIGEST);
        MediaTransformSucceededResult complete = successResult(processing);
        List<MediaRenditionResult> renditions = new ArrayList<>(complete.renditions());
        MediaRenditionResult first = renditions.getFirst();
        renditions.set(0, new MediaRenditionResult(
                first.role(),
                first.format(),
                first.width(),
                first.height(),
                first.byteSize(),
                "media/renditions/wrong.webp"
        ));
        MediaTransformSucceededResult wrongKey = new MediaTransformSucceededResult(
                complete.contractVersion(), complete.jobId(), complete.assetId(),
                complete.specVersion(), complete.specDigest(), complete.sourceVersionId(),
                complete.sourceETag(), complete.verifiedSource(), renditions);

        assertThatThrownBy(() -> resultService.apply(wrongKey))
                .isInstanceOf(MediaTransformContractException.class)
                .hasMessage("media result rendition object key is invalid");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM image_rendition", Integer.class)).isZero();
    }

    @Test
    void 검증된_source가_업로드_선언과_다르면_저장하지_않는다() {
        ProcessingAsset processing = createProcessingAsset(1, SPEC_DIGEST);
        MediaTransformSucceededResult complete = successResult(processing);
        MediaTransformSucceededResult wrongSource = new MediaTransformSucceededResult(
                complete.contractVersion(), complete.jobId(), complete.assetId(),
                complete.specVersion(), complete.specDigest(), complete.sourceVersionId(),
                complete.sourceETag(),
                new MediaVerifiedSource(
                        "image/jpeg", 1_048_575L, 3024, 4032, SOURCE_CHECKSUM),
                complete.renditions());

        assertThatThrownBy(() -> resultService.apply(wrongSource))
                .isInstanceOf(MediaTransformContractException.class)
                .hasMessage("media result verified source differs from the upload declaration");
        assertThat(find(processing.assetId()).getProcessingStatus())
                .isEqualTo(ImageProcessingStatus.PROCESSING);
    }

    @Test
    void 존재하지_않는_asset의_결과는_STALE로_처리한다() {
        ProcessingAsset missing = new ProcessingAsset(
                UUID.randomUUID(), UUID.randomUUID(), "version", "etag", 999, OTHER_DIGEST);

        assertThat(resultService.apply(failedResult(missing)).disposition())
                .isEqualTo(MediaTransformResultDisposition.STALE);
    }

    @Test
    void 장기_PROCESSING_scan은_복합_index와_시간_ID_keyset으로_페이지를_잇는다() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 27, 18, 0);
        ProcessingAsset oldest = createProcessingAsset(
                1, SPEC_DIGEST, now.minusMinutes(30));
        ProcessingAsset middle = createProcessingAsset(
                1, SPEC_DIGEST, now.minusMinutes(20));
        ProcessingAsset newest = createProcessingAsset(
                1, SPEC_DIGEST, now.minusMinutes(10));

        List<MediaProcessingRecoveryCandidate> first = recoveryReader.findBatch(
                now, now, 3, MediaProcessingRecoveryCursor.initial(), 2);
        List<MediaProcessingRecoveryCandidate> second = recoveryReader.findBatch(
                now, now, 3, first.getLast().nextCursor(), 2);

        assertThat(first).extracting(MediaProcessingRecoveryCandidate::jobId)
                .containsExactly(oldest.jobId(), middle.jobId());
        assertThat(second).extracting(MediaProcessingRecoveryCandidate::jobId)
                .containsExactly(newest.jobId());

        Map<String, Object> queryPlan = jdbcTemplate.queryForMap("""
                EXPLAIN SELECT id, current_job_id, target_processing_started_at
                FROM image_asset FORCE INDEX (idx_image_asset_processing_scan)
                WHERE cleanup_status = 'ACTIVE'
                  AND target_processing_status = 'PROCESSING'
                  AND target_processing_started_at <= ?
                  AND (
                        last_recovery_requested_at IS NULL
                        OR last_recovery_requested_at <= ?
                  )
                  AND processing_recovery_attempts < 3
                  AND (
                        target_processing_started_at > ?
                        OR (
                            target_processing_started_at = ?
                            AND id > 0
                        )
                  )
                ORDER BY target_processing_started_at, id
                LIMIT 100
                """,
                now,
                now,
                MediaProcessingRecoveryCursor.initial().startedAt(),
                MediaProcessingRecoveryCursor.initial().startedAt()
        );
        assertThat(queryPlan.get("key")).isEqualTo("idx_image_asset_processing_scan");
    }

    @Test
    void cleanup_기반은_UNBOUND_terminal_asset을_복합_index와_keyset으로_조회한다() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 27, 18, 0);
        ImageAsset older = createPendingAsset();
        ImageAsset newer = createPendingAsset();
        jdbcTemplate.update(
                "UPDATE image_asset SET updated_at = ? WHERE id = ?",
                now.minusDays(2),
                older.getId()
        );
        jdbcTemplate.update(
                "UPDATE image_asset SET updated_at = ? WHERE id = ?",
                now.minusDays(1),
                newer.getId()
        );

        List<MediaCleanupCandidate> first = cleanupCandidateReader.findUnboundBatch(
                MediaCreationOrigin.DIRECT_UPLOAD,
                EnumSet.of(
                        ImageProcessingStatus.PENDING_UPLOAD,
                        ImageProcessingStatus.EXPIRED
                ),
                now,
                MediaCleanupCandidateCursor.initial(),
                1
        );
        List<MediaCleanupCandidate> second = cleanupCandidateReader.findUnboundBatch(
                MediaCreationOrigin.DIRECT_UPLOAD,
                EnumSet.of(
                        ImageProcessingStatus.PENDING_UPLOAD,
                        ImageProcessingStatus.EXPIRED
                ),
                now,
                first.getLast().nextCursor(),
                1
        );

        assertThat(first).extracting(MediaCleanupCandidate::publicId)
                .containsExactly(older.getPublicId());
        assertThat(second).extracting(MediaCleanupCandidate::publicId)
                .containsExactly(newer.getPublicId());

        Map<String, Object> queryPlan = jdbcTemplate.queryForMap("""
                EXPLAIN SELECT id, public_id, processing_status, creation_origin, updated_at
                FROM image_asset
                WHERE cleanup_status = 'ACTIVE'
                  AND binding_status = 'UNBOUND'
                  AND processing_status IN ('PENDING_UPLOAD', 'EXPIRED')
                  AND creation_origin = 'DIRECT_UPLOAD'
                  AND target_processing_status IS NULL
                  AND updated_at <= ?
                  AND (
                        updated_at > ?
                        OR (updated_at = ? AND id > 0)
                  )
                ORDER BY updated_at, id
                LIMIT 100
                """,
                now,
                MediaCleanupCandidateCursor.initial().updatedAt(),
                MediaCleanupCandidateCursor.initial().updatedAt()
        );
        assertThat(queryPlan.get("key")).isEqualTo("idx_image_asset_cleanup_scan");
        assertThat(String.valueOf(queryPlan.get("Extra"))).doesNotContain("Using filesort");
    }

    private ProcessingAsset createProcessingAsset(int specVersion, String specDigest) {
        return createProcessingAsset(specVersion, specDigest, LocalDateTime.now());
    }

    private ImageAsset createPendingAsset() {
        UUID assetId = UUID.randomUUID();
        ImageAsset asset = ImageAsset.createDirectUpload(
                assetId,
                MediaPurpose.REVIEW,
                MediaOwnerType.USER,
                1L,
                "media/originals/%s/original".formatted(assetId),
                "image/jpeg",
                1_024L,
                LocalDateTime.now().plusMinutes(5)
        );
        return imageAssetRepository.saveAndFlush(asset);
    }

    private ProcessingAsset createProcessingAsset(
            int specVersion,
            String specDigest,
            LocalDateTime startedAt
    ) {
        UUID assetId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        ImageAsset asset = ImageAsset.createDirectUpload(
                assetId,
                MediaPurpose.REVIEW,
                MediaOwnerType.USER,
                1L,
                "media/originals/%s/original".formatted(assetId),
                "image/jpeg",
                1_048_576L,
                LocalDateTime.now().plusMinutes(5)
        );
        asset.beginInitialProcessing(
                "3Lg-source-version", "\"etag-value\"", specVersion, specDigest, jobId,
                startedAt);
        imageAssetRepository.saveAndFlush(asset);
        return new ProcessingAsset(
                assetId,
                jobId,
                "3Lg-source-version",
                "\"etag-value\"",
                specVersion,
                specDigest
        );
    }

    private MediaTransformSucceededResult successResult(ProcessingAsset processing) {
        MediaVerifiedSource source = new MediaVerifiedSource(
                "image/jpeg", 1_048_576L, 3024, 4032, SOURCE_CHECKSUM);
        List<MediaRenditionResult> renditions = List.of(
                rendition(processing, ImageRole.REVIEW_PREVIEW, 135, 135),
                rendition(processing, ImageRole.REVIEW_PREVIEW, 270, 270),
                rendition(processing, ImageRole.REVIEW_PREVIEW, 405, 405),
                rendition(processing, ImageRole.REVIEW_DETAIL, 430, 628),
                rendition(processing, ImageRole.REVIEW_DETAIL, 860, 1256),
                rendition(processing, ImageRole.REVIEW_DETAIL, 1290, 1884)
        );
        return new MediaTransformSucceededResult(
                1,
                processing.jobId(),
                processing.assetId(),
                processing.specVersion(),
                processing.specDigest(),
                processing.sourceVersionId(),
                processing.sourceEtag(),
                source,
                renditions
        );
    }

    private MediaTransformFailedResult failedResult(ProcessingAsset processing) {
        return new MediaTransformFailedResult(
                1,
                processing.jobId(),
                processing.assetId(),
                processing.specVersion(),
                processing.specDigest(),
                processing.sourceVersionId(),
                processing.sourceEtag(),
                MediaTransformFailureCode.INVALID_IMAGE_DATA
        );
    }

    private String failedBody(ProcessingAsset processing) throws JsonProcessingException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contractVersion", 1);
        body.put("jobId", processing.jobId());
        body.put("assetId", processing.assetId());
        body.put("specVersion", processing.specVersion());
        body.put("specDigest", processing.specDigest());
        body.put("status", "FAILED");
        body.put("sourceVersionId", processing.sourceVersionId());
        body.put("sourceETag", processing.sourceEtag());
        body.put("failureCode", MediaTransformFailureCode.INVALID_IMAGE_DATA.name());
        return objectMapper.writeValueAsString(body);
    }

    private String succeededBody(ProcessingAsset processing) throws JsonProcessingException {
        var body = objectMapper.valueToTree(successResult(processing));
        ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("status", "SUCCEEDED");
        return objectMapper.writeValueAsString(body);
    }

    private ImageProcessingStatus databaseStatus(UUID assetId) {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT processing_status FROM image_asset WHERE public_id = ?")) {
            statement.setString(1, assetId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException("media asset is missing");
                }
                return ImageProcessingStatus.valueOf(result.getString(1));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to read media asset status", exception);
        }
    }

    private MediaRenditionResult rendition(
            ProcessingAsset processing, ImageRole role, int width, int height) {
        String roleSegment = role.name().toLowerCase().replace('_', '-');
        return new MediaRenditionResult(
                role,
                ImageFormat.WEBP,
                width,
                height,
                10_000L + width,
                "media/renditions/%s/v%d/%s/%d.webp".formatted(
                        processing.assetId(), processing.specVersion(), roleSegment, width)
        );
    }

    private ImageAsset find(UUID assetId) {
        return imageAssetRepository.findByPublicId(assetId).orElseThrow();
    }

    private static final class RecordingAcknowledgement implements Acknowledgement {

        private final Runnable beforeAcknowledgement;
        private final AtomicBoolean acknowledged = new AtomicBoolean();

        private RecordingAcknowledgement(Runnable beforeAcknowledgement) {
            this.beforeAcknowledgement = beforeAcknowledgement;
        }

        @Override
        public void acknowledge() {
            beforeAcknowledgement.run();
            acknowledged.set(true);
        }

        @Override
        public CompletableFuture<Void> acknowledgeAsync() {
            acknowledge();
            return CompletableFuture.completedFuture(null);
        }

        private boolean acknowledged() {
            return acknowledged.get();
        }
    }

    private record ProcessingAsset(
            UUID assetId,
            UUID jobId,
            String sourceVersionId,
            String sourceEtag,
            int specVersion,
            String specDigest
    ) {
    }
}
