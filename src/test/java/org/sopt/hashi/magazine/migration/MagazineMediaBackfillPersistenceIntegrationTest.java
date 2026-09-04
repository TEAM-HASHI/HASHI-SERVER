package org.sopt.hashi.magazine.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.sopt.hashi.magazine.AdminMagazineCommand.ImageCommand;
import org.sopt.hashi.magazine.AdminMagazineCommand;
import org.sopt.hashi.magazine.domain.Magazine;
import org.sopt.hashi.magazine.domain.MagazineRepository;
import org.sopt.hashi.magazine.migration.MagazineMediaBackfillCheckpointStore.Acquisition;
import org.sopt.hashi.magazine.migration.MagazineMediaBackfillCheckpointStore.AcquisitionState;
import org.sopt.hashi.magazine.migration.MagazineMediaBackfillCheckpointStore.Lease;
import org.sopt.hashi.magazine.migration.MagazineMediaBackfillCheckpointStore.Snapshot;
import org.sopt.hashi.magazine.service.MagazineService;
import org.sopt.hashi.media.MediaAssetPurpose;
import org.sopt.hashi.media.MediaBackfillAssetInfo;
import org.sopt.hashi.media.domain.ImageAsset;
import org.sopt.hashi.media.domain.ImageAssetRepository;
import org.sopt.hashi.media.domain.ImageBindingStatus;
import org.sopt.hashi.media.domain.ImageFormat;
import org.sopt.hashi.media.domain.ImageRole;
import org.sopt.hashi.media.domain.MediaPurpose;
import org.sopt.hashi.media.internal.backfill.MediaBackfillStorage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Magazine + 실제 media claim + checkpoint의 원자성을 검증하는 교차 모듈 MySQL 통합 gate. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "jwt.secret=test-secret-key-must-be-at-least-32-bytes-long",
        "kakao.client-id=test-client-id",
        "kakao.redirect-uri=https://app.hashi.test/callback",
        "hashi.storage.cloudfront-domain=https://cdn.hashi.test",
        "hashi.media.backfill.enabled=true",
        "hashi.magazine.media-backfill.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MagazineMediaBackfillPersistenceIntegrationTest {

    private static final MagazineMediaBackfillTarget TARGET = MagazineMediaBackfillTarget.MAGAZINE_BANNER;
    private static final Duration LEASE_DURATION = Duration.ofMinutes(5);
    private static final String SPEC_DIGEST =
            "1b5759a9285732133699114e21101b3b9b43b5cd8e208bf1246d059f4293634f";
    private static final String SOURCE_CHECKSUM =
            "47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=";

    @Container
    @ServiceConnection
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("hashi")
            .withUsername("hashi")
            .withPassword("hashi");

    @Autowired
    private MagazineMediaBackfillCheckpointStore checkpointStore;
    @Autowired
    private MagazineMediaBackfillCandidateReader candidateReader;
    @Autowired
    private MagazineMediaBackfillAttachmentService attachmentService;
    @Autowired
    private MagazineRepository magazineRepository;
    @Autowired
    private ImageAssetRepository imageAssetRepository;
    @Autowired
    private MagazineService magazineService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    @Qualifier("japanClock")
    private Clock clock;
    @MockitoBean
    private MediaBackfillStorage mediaBackfillStorage;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM magazine_media_backfill_checkpoint");
        jdbcTemplate.update("DELETE FROM magazine");
        jdbcTemplate.update("DELETE FROM image_rendition");
        jdbcTemplate.update("DELETE FROM image_asset");
    }

    @Test
    void 같은_run_ID는_상한과_cursor를_보존하며_한_worker만_lease를_얻는다() {
        UUID runId = UUID.randomUUID();
        Acquisition first = checkpointStore.acquire(
                runId, TARGET, MagazineMediaBackfillMode.PREPARE, 100L, LEASE_DURATION);
        Acquisition busy = checkpointStore.acquire(
                runId, TARGET, MagazineMediaBackfillMode.PREPARE, 200L, LEASE_DURATION);

        assertThat(first.state()).isEqualTo(AcquisitionState.ACQUIRED);
        assertThat(busy.state()).isEqualTo(AcquisitionState.BUSY);
        checkpointStore.recordProgress(
                first.lease(), 10L, MagazineMediaBackfillOutcome.PREPARED, LEASE_DURATION);
        assertThat(checkpointStore.pause(first.lease())).isTrue();

        Acquisition resumed = checkpointStore.acquire(
                runId, TARGET, MagazineMediaBackfillMode.PREPARE, 200L, LEASE_DURATION);

        assertThat(resumed.state()).isEqualTo(AcquisitionState.ACQUIRED);
        assertThat(resumed.snapshot().upperBoundId()).isEqualTo(100L);
        assertThat(resumed.snapshot().cursorId()).isEqualTo(10L);
        assertThat(resumed.snapshot().preparedCount()).isEqualTo(1L);
        assertThat(resumed.lease().token()).isNotEqualTo(first.lease().token());
    }

    @Test
    void 만료된_lease의_worker는_새_worker의_cursor를_갱신하거나_중지할_수_없다() {
        UUID runId = UUID.randomUUID();
        Lease first = acquire(runId, 100L);
        expire(first);
        Lease replacement = acquire(runId, 100L);

        assertThatThrownBy(() -> checkpointStore.recordProgress(
                first, 10L, MagazineMediaBackfillOutcome.ATTACHED, LEASE_DURATION))
                .isInstanceOf(MagazineMediaBackfillLeaseLostException.class);
        assertThatThrownBy(() -> checkpointStore.complete(first))
                .isInstanceOf(MagazineMediaBackfillLeaseLostException.class);
        assertThat(checkpointStore.pause(first)).isFalse();
        checkpointStore.recordProgress(
                replacement, 20L, MagazineMediaBackfillOutcome.SKIPPED, LEASE_DURATION);

        Snapshot snapshot = checkpointStore.find(runId);
        assertThat(snapshot.cursorId()).isEqualTo(20L);
        assertThat(snapshot.scannedCount()).isEqualTo(1L);
        assertThat(snapshot.skippedCount()).isEqualTo(1L);
        assertThat(snapshot.attachedCount()).isZero();
    }

    @Test
    void checkpoint_잠금_대기_중_만료된_lease는_갱신하지_않는다() throws Exception {
        UUID runId = UUID.randomUUID();
        Lease lease = checkpointStore.acquire(
                runId, TARGET, MagazineMediaBackfillMode.ATTACH, 10L, Duration.ofSeconds(3)).lease();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT run_id FROM magazine_media_backfill_checkpoint WHERE run_id = ? FOR UPDATE
                    """)) {
                statement.setString(1, runId.toString());
                try (ResultSet ignored = statement.executeQuery()) {
                    assertThat(ignored.next()).isTrue();
                }
            }
            Future<?> progress = executor.submit(() -> checkpointStore.recordProgress(
                    lease, 1L, MagazineMediaBackfillOutcome.SKIPPED, LEASE_DURATION));
            try {
                awaitTableLockWait("magazine_media_backfill_checkpoint");
                awaitLeaseExpiry(runId);
            } finally {
                connection.commit();
            }

            assertThatThrownBy(() -> progress.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(MagazineMediaBackfillLeaseLostException.class);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertThat(checkpointStore.find(runId).scannedCount()).isZero();
        assertThat(checkpointStore.find(runId).cursorId()).isZero();
    }

    @Test
    void 같은_run_ID의_target과_mode는_변경하지_않고_거부_후에도_기록을_보존한다() {
        UUID runId = UUID.randomUUID();
        Lease lease = acquire(runId, 10L);
        checkpointStore.pause(lease);
        Snapshot before = checkpointStore.find(runId);

        assertThatThrownBy(() -> checkpointStore.acquire(
                runId, TARGET, MagazineMediaBackfillMode.PREPARE, 10L, LEASE_DURATION))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasRootCauseExactlyInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage("runId is already assigned to a different backfill execution");
        assertThatThrownBy(() -> checkpointStore.acquire(
                runId, MagazineMediaBackfillTarget.MAGAZINE_THUMBNAIL,
                MagazineMediaBackfillMode.ATTACH, 10L, LEASE_DURATION))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasRootCauseExactlyInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage("runId is already assigned to a different backfill execution");
        assertThat(checkpointStore.find(runId)).isEqualTo(before);
    }

    @Test
    void 완료된_run_ID는_새_상한이_생겨도_다시_실행하지_않는다() {
        UUID runId = UUID.randomUUID();
        Lease lease = acquire(runId, 10L);
        checkpointStore.complete(lease);

        Acquisition completed = checkpointStore.acquire(
                runId, TARGET, MagazineMediaBackfillMode.ATTACH, 20L, LEASE_DURATION);

        assertThat(completed.state()).isEqualTo(AcquisitionState.COMPLETED);
        assertThat(completed.snapshot().upperBoundId()).isEqualTo(10L);
        assertThat(completed.snapshot().cursorId()).isEqualTo(10L);
    }

    @Test
    void V24는_잘못된_target_mode_cursor_lease와_집계값을_DB에서도_거부한다() {
        UUID runId = UUID.randomUUID();
        Lease lease = acquire(runId, 10L);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE magazine_media_backfill_checkpoint SET target = 'USER_PROFILE' WHERE run_id = ?",
                runId.toString())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE magazine_media_backfill_checkpoint SET mode = 'DRY_RUN' WHERE run_id = ?",
                runId.toString())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE magazine_media_backfill_checkpoint SET cursor_id = 11 WHERE run_id = ?",
                runId.toString())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE magazine_media_backfill_checkpoint SET lease_token = NULL WHERE run_id = ?",
                runId.toString())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE magazine_media_backfill_checkpoint SET attached_count = 1 WHERE run_id = ?",
                runId.toString())).isInstanceOf(DataAccessException.class);
        assertThat(checkpointStore.pause(lease)).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.key_column_usage
                WHERE constraint_schema = DATABASE()
                  AND table_name = 'magazine_media_backfill_checkpoint'
                  AND referenced_table_name IS NOT NULL
                """, Integer.class)).isZero();
    }


    @ParameterizedTest
    @EnumSource(MagazineMediaBackfillTarget.class)
    void keyset은_활성_legacy_슬롯만_읽고_고정_상한_이후_매거진은_제외한다(MagazineMediaBackfillTarget target) {
        Magazine first = saveMagazine("first");
        Magazine deleted = saveMagazine("deleted");
        magazineService.delete(deleted.getId());
        magazineRepository.saveAndFlush(Magazine.create(
                "asset only", null, UUID.randomUUID(), null, UUID.randomUUID(), "https://example.test/"));
        Magazine migrated = saveMagazine("migrated");
        transactionTemplate.executeWithoutResult(status -> {
            Magazine locked = magazineRepository.findByIdForUpdate(migrated.getId()).orElseThrow();
            attachSlot(locked, target, UUID.randomUUID());
        });
        Magazine last = saveMagazine("last");
        long upper = candidateReader.findUpperBound(target);
        saveMagazine("new");

        List<MagazineMediaBackfillCandidate> firstPage = candidateReader.findBatch(target, 0L, upper, 1);
        List<MagazineMediaBackfillCandidate> secondPage = candidateReader.findBatch(
                target, firstPage.getFirst().magazineId(), upper, 10);

        assertThat(upper).isEqualTo(last.getId());
        assertThat(firstPage).extracting(MagazineMediaBackfillCandidate::magazineId)
                .containsExactly(first.getId());
        assertThat(secondPage).extracting(MagazineMediaBackfillCandidate::magazineId)
                .containsExactly(last.getId());
        assertThat(magazineRepository.findById(deleted.getId())).isEmpty();
        assertThat(candidateReader.findBatch(other(target), 0L, upper, 10))
                .extracting(MagazineMediaBackfillCandidate::magazineId)
                .contains(migrated.getId());
    }

    @ParameterizedTest
    @EnumSource(MagazineMediaBackfillTarget.class)
    void READY_슬롯은_기존_key와_표시정보를_보존하며_claim과_cursor를_commit한다(MagazineMediaBackfillTarget target) {
        Magazine magazine = saveMagazine("legacy");
        Map<String, Object> before = magazineDetails(magazine.getId());
        ImageAsset asset = readyAsset(target);
        Lease lease = acquire(UUID.randomUUID(), target, magazine.getId());

        assertThat(attachmentService.attachAndRecord(
                candidate(magazine, target), info(asset), lease, LEASE_DURATION))
                .isEqualTo(MagazineMediaBackfillOutcome.ATTACHED);

        assertThat(magazineDetails(magazine.getId())).isEqualTo(before);
        Magazine current = magazineRepository.findById(magazine.getId()).orElseThrow();
        assertThat(slotAsset(current, target)).isEqualTo(asset.getPublicId());
        assertThat(slotAsset(current, other(target))).isNull();
        assertBound(asset, ImageBindingStatus.BOUND);
        Snapshot snapshot = checkpointStore.find(lease.runId());
        assertThat(snapshot.cursorId()).isEqualTo(magazine.getId());
        assertThat(snapshot.scannedCount()).isEqualTo(1);
        assertThat(snapshot.attachedCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(MagazineMediaBackfillTarget.class)
    void lease_fence_실패는_슬롯_UUID_claim_cursor를_모두_rollback한다(MagazineMediaBackfillTarget target) {
        Magazine magazine = saveMagazine("rollback");
        Map<String, Object> before = magazineDetails(magazine.getId());
        ImageAsset asset = readyAsset(target);
        Lease lease = acquire(UUID.randomUUID(), target, magazine.getId());
        expire(lease);
        Snapshot checkpointBefore = checkpointStore.find(lease.runId());

        assertThatThrownBy(() -> attachmentService.attachAndRecord(
                candidate(magazine, target), info(asset), lease, LEASE_DURATION))
                .isInstanceOf(MagazineMediaBackfillLeaseLostException.class);

        assertThat(magazineDetails(magazine.getId())).isEqualTo(before);
        assertThat(slotAsset(magazineRepository.findById(magazine.getId()).orElseThrow(), target)).isNull();
        assertBound(asset, ImageBindingStatus.UNBOUND);
        assertThat(checkpointStore.find(lease.runId())).isEqualTo(checkpointBefore);
    }

    @ParameterizedTest
    @EnumSource(MagazineMediaBackfillTarget.class)
    void 삭제가_먼저_commit되면_attach는_삭제_상태와_기존_슬롯을_유지한다(
            MagazineMediaBackfillTarget target
    ) throws Exception {
        Magazine magazine = saveMagazine("deleted");
        ImageAsset asset = readyAsset(target);
        Lease lease = acquire(UUID.randomUUID(), target, magazine.getId());

        MagazineMediaBackfillOutcome outcome = raceAgainstOwnerWrite(magazine, asset, lease, () -> {
            magazineService.delete(magazine.getId());
            magazineRepository.flush();
        });

        assertThat(outcome).isEqualTo(MagazineMediaBackfillOutcome.SKIPPED);
        assertThat(magazineRepository.findById(magazine.getId())).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted FROM magazine WHERE id = ?", Boolean.class, magazine.getId())).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT banner_image_asset_id FROM magazine WHERE id = ?", String.class, magazine.getId())).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT thumbnail_image_asset_id FROM magazine WHERE id = ?", String.class, magazine.getId())).isNull();
        assertBound(asset, ImageBindingStatus.UNBOUND);
        assertThat(checkpointStore.find(lease.runId()).skippedCount()).isEqualTo(1L);
    }

    @ParameterizedTest
    @EnumSource(MagazineMediaBackfillTarget.class)
    void 실제_이미지_수정이_먼저_commit되면_attach는_이전_사진을_연결하지_않는다(
            MagazineMediaBackfillTarget target
    ) throws Exception {
        Magazine magazine = saveMagazine("old");
        ImageAsset asset = readyAsset(target);
        Lease lease = acquire(UUID.randomUUID(), target, magazine.getId());
        ImageCommand replacement = new ImageCommand("magazines/new.jpg", null);

        MagazineMediaBackfillOutcome outcome = raceAgainstOwnerWrite(magazine, asset, lease, () -> {
            magazineService.update(magazine.getId(), new AdminMagazineCommand(
                    null, target == TARGET ? replacement : null,
                    target == TARGET ? null : replacement, null));
            magazineRepository.flush();
        });

        assertThat(outcome).isEqualTo(MagazineMediaBackfillOutcome.SKIPPED);
        Magazine current = magazineRepository.findById(magazine.getId()).orElseThrow();
        assertThat(slotKey(current, target)).isEqualTo("magazines/new.jpg");
        assertThat(slotKey(current, other(target))).isEqualTo(slotKey(magazine, other(target)));
        assertThat(slotAsset(current, target)).isNull();
        assertBound(asset, ImageBindingStatus.UNBOUND);
        assertThat(checkpointStore.find(lease.runId()).skippedCount()).isEqualTo(1L);
    }

    @ParameterizedTest
    @EnumSource(MagazineMediaBackfillTarget.class)
    void 제목만_바뀌면_사진은_연결하면서_새_제목과_URL을_보존한다(
            MagazineMediaBackfillTarget target
    ) throws Exception {
        Magazine magazine = saveMagazine("title-only");
        ImageAsset asset = readyAsset(target);
        Lease lease = acquire(UUID.randomUUID(), target, magazine.getId());

        MagazineMediaBackfillOutcome outcome = raceAgainstOwnerWrite(magazine, asset, lease, () -> {
            magazineService.update(magazine.getId(), new AdminMagazineCommand(
                    "새 제목", null, null, "https://example.test/updated"));
            magazineRepository.flush();
        });

        assertThat(outcome).isEqualTo(MagazineMediaBackfillOutcome.ATTACHED);
        Magazine current = magazineRepository.findById(magazine.getId()).orElseThrow();
        assertThat(current.getTitle()).isEqualTo("새 제목");
        assertThat(current.getInstagramRedirectUrl()).isEqualTo("https://example.test/updated");
        assertThat(slotAsset(current, target)).isEqualTo(asset.getPublicId());
        assertBound(asset, ImageBindingStatus.BOUND);
    }

    @ParameterizedTest
    @EnumSource(MagazineMediaBackfillTarget.class)
    void 서로_다른_run이_같은_슬롯을_연결해도_한_asset만_claim한다(
            MagazineMediaBackfillTarget target
    ) throws Exception {
        Magazine magazine = saveMagazine("shared-slot");
        ImageAsset firstAsset = readyAsset(target);
        ImageAsset secondAsset = readyAsset(target);
        Lease firstLease = acquire(UUID.randomUUID(), target, magazine.getId());
        Lease secondLease = acquire(UUID.randomUUID(), target, magazine.getId());

        MagazineMediaBackfillOutcome secondOutcome = raceAgainstOwnerWrite(
                magazine, secondAsset, secondLease, () -> {
                    assertThat(attachmentService.attachAndRecord(
                            candidate(magazine, target), info(firstAsset), firstLease, LEASE_DURATION))
                            .isEqualTo(MagazineMediaBackfillOutcome.ATTACHED);
                    magazineRepository.flush();
                });

        assertThat(secondOutcome).isEqualTo(MagazineMediaBackfillOutcome.SKIPPED);
        assertThat(slotAsset(magazineRepository.findById(magazine.getId()).orElseThrow(), target))
                .isEqualTo(firstAsset.getPublicId());
        assertBound(firstAsset, ImageBindingStatus.BOUND);
        assertBound(secondAsset, ImageBindingStatus.UNBOUND);
        assertThat(checkpointStore.find(firstLease.runId()).attachedCount()).isEqualTo(1);
        assertThat(checkpointStore.find(secondLease.runId()).skippedCount()).isEqualTo(1);
    }

    @Test
    void 배너와_썸네일을_동시에_전환해도_각_슬롯과_진행기록을_따로_보존한다() throws Exception {
        Magazine magazine = saveMagazine("both-slots");
        ImageAsset banner = readyAsset(TARGET);
        MagazineMediaBackfillTarget thumbnailTarget = other(TARGET);
        ImageAsset thumbnail = readyAsset(thumbnailTarget);
        Lease bannerLease = acquire(UUID.randomUUID(), TARGET, magazine.getId());
        Lease thumbnailLease = acquire(UUID.randomUUID(), thumbnailTarget, magazine.getId());

        MagazineMediaBackfillOutcome thumbnailOutcome = raceAgainstOwnerWrite(
                magazine, thumbnail, thumbnailLease, () -> {
                    attachmentService.attachAndRecord(
                            candidate(magazine, TARGET), info(banner), bannerLease, LEASE_DURATION);
                    magazineRepository.flush();
                });

        assertThat(thumbnailOutcome).isEqualTo(MagazineMediaBackfillOutcome.ATTACHED);
        Magazine current = magazineRepository.findById(magazine.getId()).orElseThrow();
        assertThat(current.getBannerImageAssetId()).isEqualTo(banner.getPublicId());
        assertThat(current.getThumbnailImageAssetId()).isEqualTo(thumbnail.getPublicId());
        assertThat(current.getBannerKey()).isEqualTo(magazine.getBannerKey());
        assertThat(current.getThumbnailKey()).isEqualTo(magazine.getThumbnailKey());
        assertBound(banner, ImageBindingStatus.BOUND);
        assertBound(thumbnail, ImageBindingStatus.BOUND);
        assertThat(checkpointStore.find(bannerLease.runId()).attachedCount()).isEqualTo(1);
        assertThat(checkpointStore.find(thumbnailLease.runId()).attachedCount()).isEqualTo(1);
    }

    @Test
    void checkpoint에는_경로_콘텐츠_정보_또는_asset_식별자_컬럼을_추가하지_않는다() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'magazine_media_backfill_checkpoint'
                ORDER BY ordinal_position
                """, String.class)).containsExactly(
                "run_id", "target", "mode", "status", "upper_bound_id", "cursor_id", "lease_token", "lease_until",
                "scanned_count", "prepared_count", "attached_count", "skipped_count", "failed_count",
                "created_at", "updated_at");
    }

    @ParameterizedTest
    @EnumSource(MagazineMediaBackfillTarget.class)
    void keyset_후보_조회는_기존_PK_또는_슬롯_asset_인덱스로_범위를_제한한다(MagazineMediaBackfillTarget target) {
        List<Object[]> rows = new ArrayList<>();
        for (int index = 1; index <= 2_000; index++) {
            rows.add(new Object[]{"plan-" + index, "magazines/banner.jpg", "magazines/thumbnail.jpg"});
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO magazine (title, banner_key, thumbnail_key, instagram_redirect_url,
                                      deleted, created_at, updated_at)
                VALUES (?, ?, ?, 'https://example.test/', false, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, rows);
        jdbcTemplate.execute("ANALYZE TABLE magazine");
        long upper = candidateReader.findUpperBound(target);
        // 열 이름은 외부 입력이 아닌 고정된 두 target 중 하나에서만 선택한다.
        String slot = target == TARGET ? "banner" : "thumbnail";
        Map<String, Object> plan = jdbcTemplate.queryForMap("""
                EXPLAIN SELECT id, %s_key AS legacy_key
                FROM magazine
                WHERE deleted = false AND %s_key IS NOT NULL AND %s_image_asset_id IS NULL
                  AND id > ? AND id <= ?
                ORDER BY id ASC LIMIT 50
                """.formatted(slot, slot, slot), upper - 1_000, upper);

        assertThat(plan.get("type")).as("keyset query plan: %s", plan).isIn("range", "ref");
        assertThat(plan.get("key")).as("keyset query plan: %s", plan)
                .isIn("PRIMARY", "uq_magazine_" + slot + "_image_asset_id");
    }

    private Lease acquire(UUID runId, long upperBound) {
        return acquire(runId, TARGET, upperBound);
    }

    private Lease acquire(UUID runId, MagazineMediaBackfillTarget target, long upperBound) {
        Acquisition acquisition = checkpointStore.acquire(
                runId, target, MagazineMediaBackfillMode.ATTACH, upperBound, LEASE_DURATION);
        assertThat(acquisition.state()).isEqualTo(AcquisitionState.ACQUIRED);
        return acquisition.lease();
    }

    private void expire(Lease lease) {
        jdbcTemplate.update("""
                UPDATE magazine_media_backfill_checkpoint
                SET lease_until = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6))
                WHERE run_id = ?
                """, lease.runId().toString());
    }

    private MagazineMediaBackfillTarget other(MagazineMediaBackfillTarget target) {
        return target == TARGET ? MagazineMediaBackfillTarget.MAGAZINE_THUMBNAIL : TARGET;
    }

    private MagazineMediaBackfillCandidate candidate(Magazine magazine, MagazineMediaBackfillTarget target) {
        return new MagazineMediaBackfillCandidate(target, magazine.getId(), slotKey(magazine, target));
    }

    private String slotKey(Magazine magazine, MagazineMediaBackfillTarget target) {
        return target == TARGET ? magazine.getBannerKey() : magazine.getThumbnailKey();
    }

    private UUID slotAsset(Magazine magazine, MagazineMediaBackfillTarget target) {
        return target == TARGET ? magazine.getBannerImageAssetId() : magazine.getThumbnailImageAssetId();
    }

    private void attachSlot(Magazine magazine, MagazineMediaBackfillTarget target, UUID assetId) {
        boolean attached = target == TARGET
                ? magazine.attachBackfilledBanner(magazine.getBannerKey(), assetId)
                : magazine.attachBackfilledThumbnail(magazine.getThumbnailKey(), assetId);
        assertThat(attached).isTrue();
    }

    private ImageAsset readyAsset(MagazineMediaBackfillTarget target) {
        UUID assetId = UUID.randomUUID();
        String identity = UUID.randomUUID().toString().replace("-", "").repeat(2);
        LocalDateTime now = LocalDateTime.now(clock);
        MediaPurpose purpose = MediaPurpose.valueOf(target.mediaTarget().purpose().name());
        ImageAsset asset = ImageAsset.createSystemBackfill(assetId, purpose,
                "media/originals/%s/original".formatted(assetId), "image/jpeg", 1024L,
                now.plusMinutes(5), identity);
        UUID jobId = UUID.randomUUID();
        asset.beginInitialProcessing("version-1", "\"etag-1\"", 1, SPEC_DIGEST, jobId, now);
        ImageRole role = ImageRole.valueOf(purpose.name());
        int[][] sizes = target == TARGET
                ? new int[][]{{390, 177}, {780, 354}, {1170, 530}}
                : new int[][]{{156, 88}, {312, 176}, {468, 264}};
        for (int[] size : sizes) {
            asset.addRendition(jobId, 1, SPEC_DIGEST, role, ImageFormat.WEBP, size[0], size[1], 100L,
                    "media/renditions/%s/v1/%s/%d.webp".formatted(assetId, role.name().toLowerCase(), size[0]));
        }
        asset.completeCurrentProcessing(
                jobId, 1, SPEC_DIGEST, "image/jpeg", 1024L, 3024, 4032, SOURCE_CHECKSUM);
        return imageAssetRepository.saveAndFlush(asset);
    }

    private MediaBackfillAssetInfo info(ImageAsset asset) {
        return new MediaBackfillAssetInfo(asset.getPublicId(), MediaAssetPurpose.valueOf(asset.getPurpose().name()),
                asset.getBackfillIdentityHash(), MediaBackfillAssetInfo.State.READY);
    }

    private void assertBound(ImageAsset asset, ImageBindingStatus status) {
        assertThat(imageAssetRepository.findByPublicId(asset.getPublicId()).orElseThrow().getBindingStatus())
                .isEqualTo(status);
    }

    private Magazine saveMagazine(String marker) {
        return magazineRepository.saveAndFlush(Magazine.create(
                marker, "magazines/" + marker + "-banner.jpg", "magazines/" + marker + "-thumbnail.jpg",
                "https://example.test/"));
    }

    private Map<String, Object> magazineDetails(long magazineId) {
        return jdbcTemplate.queryForMap("""
                SELECT id, title, banner_key, thumbnail_key, instagram_redirect_url, deleted, created_at
                FROM magazine WHERE id = ?
                """, magazineId);
    }

    private MagazineMediaBackfillOutcome raceAgainstOwnerWrite(
            Magazine magazine, ImageAsset asset, Lease lease, Runnable ownerWrite
    ) throws Exception {
        CountDownLatch changed = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> update = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                ownerWrite.run();
                changed.countDown();
                await(allowCommit);
            }));
            assertThat(changed.await(10, TimeUnit.SECONDS)).isTrue();
            Future<MagazineMediaBackfillOutcome> attach = executor.submit(() ->
                    attachmentService.attachAndRecord(
                            candidate(magazine, lease.target()), info(asset), lease, LEASE_DURATION));
            try {
                awaitTableLockWait("magazine");
            } finally {
                allowCommit.countDown();
            }
            update.get(20, TimeUnit.SECONDS);
            return attach.get(20, TimeUnit.SECONDS);
        } finally {
            allowCommit.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private void awaitTableLockWait(String tableName) throws SQLException, InterruptedException {
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*)
                     FROM performance_schema.data_lock_waits waits
                     JOIN performance_schema.data_locks requested
                       ON requested.ENGINE_LOCK_ID = waits.REQUESTING_ENGINE_LOCK_ID
                      AND requested.ENGINE = waits.ENGINE
                     WHERE requested.OBJECT_SCHEMA = ? AND requested.OBJECT_NAME = ?
                     """)) {
            statement.setString(1, MYSQL.getDatabaseName());
            statement.setString(2, tableName);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    if (resultSet.getInt(1) > 0) {
                        return;
                    }
                }
                Thread.sleep(50);
            }
        }
        throw new AssertionError("backfill row-lock wait was not observed");
    }

    private void awaitLeaseExpiry(UUID runId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Boolean expired = jdbcTemplate.queryForObject("""
                    SELECT CURRENT_TIMESTAMP(6) >= lease_until
                    FROM magazine_media_backfill_checkpoint WHERE run_id = ?
                    """, Boolean.class, runId.toString());
            if (Boolean.TRUE.equals(expired)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("backfill lease did not expire within the test bound");
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("backfill transaction wait timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("backfill transaction wait was interrupted");
        }
    }
}
