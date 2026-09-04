package org.sopt.hashi.user.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
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
import org.sopt.hashi.media.MediaAssetPurpose;
import org.sopt.hashi.media.MediaBackfillAssetInfo;
import org.sopt.hashi.media.domain.ImageAsset;
import org.sopt.hashi.media.domain.ImageAssetRepository;
import org.sopt.hashi.media.domain.ImageBindingStatus;
import org.sopt.hashi.media.domain.MediaPurpose;
import org.sopt.hashi.media.internal.backfill.MediaBackfillStorage;
import org.sopt.hashi.user.domain.User;
import org.sopt.hashi.user.domain.UserRepository;
import org.sopt.hashi.user.migration.UserProfileBackfillCheckpointStore.Acquisition;
import org.sopt.hashi.user.migration.UserProfileBackfillCheckpointStore.AcquisitionState;
import org.sopt.hashi.user.migration.UserProfileBackfillCheckpointStore.Lease;
import org.sopt.hashi.user.migration.UserProfileBackfillCheckpointStore.Snapshot;
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

/** User + 실제 media claim + checkpoint의 원자성을 검증하는 교차 모듈 MySQL 통합 gate. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "jwt.secret=test-secret-key-must-be-at-least-32-bytes-long",
        "kakao.client-id=test-client-id",
        "kakao.redirect-uri=https://app.hashi.test/callback",
        "hashi.storage.cloudfront-domain=https://cdn.hashi.test",
        "hashi.media.backfill.enabled=true",
        "hashi.user.profile-backfill.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UserProfileBackfillPersistenceIntegrationTest {

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
    private UserProfileBackfillCheckpointStore checkpointStore;
    @Autowired
    private UserProfileBackfillCandidateReader candidateReader;
    @Autowired
    private UserProfileBackfillAttachmentService attachmentService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ImageAssetRepository imageAssetRepository;
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
        jdbcTemplate.update("DELETE FROM user_profile_backfill_checkpoint");
        jdbcTemplate.update("DELETE FROM users");
        jdbcTemplate.update("DELETE FROM image_rendition");
        jdbcTemplate.update("DELETE FROM image_asset");
    }

    @Test
    void 같은_run_ID는_상한과_cursor를_보존하며_한_worker만_lease를_얻는다() {
        UUID runId = UUID.randomUUID();
        Acquisition first = checkpointStore.acquire(
                runId, UserProfileBackfillMode.PREPARE, 100L, LEASE_DURATION);
        Acquisition busy = checkpointStore.acquire(
                runId, UserProfileBackfillMode.PREPARE, 200L, LEASE_DURATION);

        assertThat(first.state()).isEqualTo(AcquisitionState.ACQUIRED);
        assertThat(busy.state()).isEqualTo(AcquisitionState.BUSY);
        checkpointStore.recordProgress(
                first.lease(), 10L, UserProfileBackfillOutcome.PREPARED, LEASE_DURATION);
        assertThat(checkpointStore.pause(first.lease())).isTrue();

        Acquisition resumed = checkpointStore.acquire(
                runId, UserProfileBackfillMode.PREPARE, 200L, LEASE_DURATION);

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
                first, 10L, UserProfileBackfillOutcome.ATTACHED, LEASE_DURATION))
                .isInstanceOf(UserProfileBackfillLeaseLostException.class);
        assertThat(checkpointStore.pause(first)).isFalse();
        checkpointStore.recordProgress(
                replacement, 20L, UserProfileBackfillOutcome.SKIPPED, LEASE_DURATION);

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
                runId, UserProfileBackfillMode.ATTACH, 10L, Duration.ofSeconds(3)).lease();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT run_id FROM user_profile_backfill_checkpoint WHERE run_id = ? FOR UPDATE
                    """)) {
                statement.setString(1, runId.toString());
                try (ResultSet ignored = statement.executeQuery()) {
                    assertThat(ignored.next()).isTrue();
                }
            }
            Future<?> progress = executor.submit(() -> checkpointStore.recordProgress(
                    lease, 1L, UserProfileBackfillOutcome.SKIPPED, LEASE_DURATION));
            try {
                awaitTableLockWait("user_profile_backfill_checkpoint");
                awaitLeaseExpiry(runId);
            } finally {
                connection.commit();
            }

            assertThatThrownBy(() -> progress.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(UserProfileBackfillLeaseLostException.class);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertThat(checkpointStore.find(runId).scannedCount()).isZero();
        assertThat(checkpointStore.find(runId).cursorId()).isZero();
    }

    @Test
    void 같은_run_ID의_mode는_변경하지_않고_거부_후에도_기록을_보존한다() {
        UUID runId = UUID.randomUUID();
        Lease lease = acquire(runId, 10L);
        checkpointStore.pause(lease);
        Snapshot before = checkpointStore.find(runId);

        assertThatThrownBy(() -> checkpointStore.acquire(
                runId, UserProfileBackfillMode.PREPARE, 10L, LEASE_DURATION))
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
                runId, UserProfileBackfillMode.ATTACH, 20L, LEASE_DURATION);

        assertThat(completed.state()).isEqualTo(AcquisitionState.COMPLETED);
        assertThat(completed.snapshot().upperBoundId()).isEqualTo(10L);
        assertThat(completed.snapshot().cursorId()).isEqualTo(10L);
    }

    @Test
    void V23은_잘못된_mode_cursor_lease와_집계값을_DB에서도_거부한다() {
        UUID runId = UUID.randomUUID();
        Lease lease = acquire(runId, 10L);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE user_profile_backfill_checkpoint SET mode = 'DRY_RUN' WHERE run_id = ?",
                runId.toString())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE user_profile_backfill_checkpoint SET cursor_id = 11 WHERE run_id = ?",
                runId.toString())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE user_profile_backfill_checkpoint SET lease_token = NULL WHERE run_id = ?",
                runId.toString())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE user_profile_backfill_checkpoint SET attached_count = 1 WHERE run_id = ?",
                runId.toString())).isInstanceOf(DataAccessException.class);
        assertThat(checkpointStore.pause(lease)).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.key_column_usage
                WHERE constraint_schema = DATABASE()
                  AND table_name = 'user_profile_backfill_checkpoint'
                  AND referenced_table_name IS NOT NULL
                """, Integer.class)).isZero();
    }

    @Test
    void keyset은_활성_legacy_프로필만_읽고_고정_상한_이후_신규_회원은_제외한다() {
        User first = saveUser("profiles/first.jpg");
        User deleted = saveUser("profiles/deleted.jpg");
        userRepository.deleteById(deleted.getId());
        saveUser(null);
        User alreadyMigrated = saveUser("profiles/migrated.jpg");
        transactionTemplate.executeWithoutResult(status -> {
            User locked = userRepository.findByIdForUpdate(alreadyMigrated.getId()).orElseThrow();
            locked.attachBackfilledProfileImage(locked.getProfileImageKey(), UUID.randomUUID());
        });
        User last = saveUser("profiles/last.jpg");
        long upper = candidateReader.findUpperBound();
        saveUser("profiles/new.jpg");

        List<UserProfileBackfillCandidate> firstPage = candidateReader.findBatch(0L, upper, 1);
        List<UserProfileBackfillCandidate> secondPage = candidateReader.findBatch(
                firstPage.getFirst().userId(), upper, 10);

        assertThat(upper).isEqualTo(last.getId());
        assertThat(firstPage).extracting(UserProfileBackfillCandidate::userId).containsExactly(first.getId());
        assertThat(secondPage).extracting(UserProfileBackfillCandidate::userId).containsExactly(last.getId());
        assertThat(userRepository.findById(deleted.getId())).isEmpty();
    }

    @Test
    void READY_프로필은_회원_정보와_legacy_key를_보존하며_claim과_cursor를_같이_commit한다() {
        User user = saveUser("profiles/legacy.jpg");
        Map<String, Object> before = userDetails(user.getId());
        ImageAsset asset = readyAsset();
        Lease lease = acquire(UUID.randomUUID(), user.getId());

        assertThat(attachmentService.attachAndRecord(candidate(user), info(asset), lease, LEASE_DURATION))
                .isEqualTo(UserProfileBackfillOutcome.ATTACHED);

        assertThat(userDetails(user.getId())).isEqualTo(before);
        assertThat(userRepository.findById(user.getId()).orElseThrow().getProfileImageAssetId())
                .isEqualTo(asset.getPublicId());
        assertBound(asset, ImageBindingStatus.BOUND);
        Snapshot snapshot = checkpointStore.find(lease.runId());
        assertThat(snapshot.cursorId()).isEqualTo(user.getId());
        assertThat(snapshot.scannedCount()).isEqualTo(1);
        assertThat(snapshot.attachedCount()).isEqualTo(1);
    }

    @Test
    void lease_fence_실패는_프로필_UUID_media_claim과_cursor를_모두_rollback한다() {
        User user = saveUser("profiles/rollback.jpg");
        Map<String, Object> before = userDetails(user.getId());
        ImageAsset asset = readyAsset();
        Lease lease = acquire(UUID.randomUUID(), user.getId());
        expire(lease);
        Snapshot checkpointBefore = checkpointStore.find(lease.runId());

        assertThatThrownBy(() -> attachmentService.attachAndRecord(
                candidate(user), info(asset), lease, LEASE_DURATION))
                .isInstanceOf(UserProfileBackfillLeaseLostException.class);

        assertThat(userDetails(user.getId())).isEqualTo(before);
        assertThat(userRepository.findById(user.getId()).orElseThrow().getProfileImageAssetId()).isNull();
        assertBound(asset, ImageBindingStatus.UNBOUND);
        assertThat(checkpointStore.find(lease.runId())).isEqualTo(checkpointBefore);
    }

    @Test
    void 탈퇴가_먼저_commit되면_잠금_대기하던_attach는_탈퇴_상태를_유지한다() throws Exception {
        User user = saveUser("profiles/deleted.jpg");
        ImageAsset asset = readyAsset();
        Lease lease = acquire(UUID.randomUUID(), user.getId());

        UserProfileBackfillOutcome outcome = raceAgainstOwnerWrite(user, asset, lease, () -> {
            userRepository.deleteById(user.getId());
            userRepository.flush();
        });

        assertThat(outcome).isEqualTo(UserProfileBackfillOutcome.SKIPPED);
        assertThat(userRepository.findById(user.getId())).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted FROM users WHERE id = ?", Boolean.class, user.getId())).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT profile_image_asset_id FROM users WHERE id = ?", String.class, user.getId())).isNull();
        assertBound(asset, ImageBindingStatus.UNBOUND);
        assertThat(checkpointStore.find(lease.runId()).skippedCount()).isEqualTo(1L);
    }

    @Test
    void source_수정이_먼저_commit되면_잠금_대기하던_attach는_이전_사진을_연결하지_않는다() throws Exception {
        User user = saveUser("profiles/old.jpg");
        ImageAsset asset = readyAsset();
        Lease lease = acquire(UUID.randomUUID(), user.getId());

        // 현재 profile 수정 API는 없다. owner UPDATE가 같은 User 행을 잠그는 DB 경쟁을 검증한다.
        UserProfileBackfillOutcome outcome = raceAgainstOwnerWrite(user, asset, lease, () ->
                jdbcTemplate.update("UPDATE users SET profile_image_key = ? WHERE id = ?",
                        "profiles/new.jpg", user.getId()));

        assertThat(outcome).isEqualTo(UserProfileBackfillOutcome.SKIPPED);
        User current = userRepository.findById(user.getId()).orElseThrow();
        assertThat(current.getProfileImageKey()).isEqualTo("profiles/new.jpg");
        assertThat(current.getProfileImageAssetId()).isNull();
        assertBound(asset, ImageBindingStatus.UNBOUND);
        assertThat(checkpointStore.find(lease.runId()).skippedCount()).isEqualTo(1L);
    }

    @Test
    void 서로_다른_run이_같은_프로필을_연결해도_한_asset만_claim한다() throws Exception {
        User user = saveUser("profiles/shared-slot.jpg");
        ImageAsset firstAsset = readyAsset();
        ImageAsset secondAsset = readyAsset();
        Lease firstLease = acquire(UUID.randomUUID(), user.getId());
        Lease secondLease = acquire(UUID.randomUUID(), user.getId());

        UserProfileBackfillOutcome secondOutcome = raceAgainstOwnerWrite(user, secondAsset, secondLease,
                () -> assertThat(attachmentService.attachAndRecord(
                        candidate(user), info(firstAsset), firstLease, LEASE_DURATION))
                        .isEqualTo(UserProfileBackfillOutcome.ATTACHED));

        assertThat(secondOutcome).isEqualTo(UserProfileBackfillOutcome.SKIPPED);
        assertThat(userRepository.findById(user.getId()).orElseThrow().getProfileImageAssetId())
                .isEqualTo(firstAsset.getPublicId());
        assertBound(firstAsset, ImageBindingStatus.BOUND);
        assertBound(secondAsset, ImageBindingStatus.UNBOUND);
        assertThat(checkpointStore.find(firstLease.runId()).attachedCount()).isEqualTo(1);
        assertThat(checkpointStore.find(secondLease.runId()).skippedCount()).isEqualTo(1);
    }

    @Test
    void checkpoint에는_개인정보_경로_또는_asset_식별자_컬럼을_추가하지_않는다() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'user_profile_backfill_checkpoint'
                ORDER BY ordinal_position
                """, String.class)).containsExactly(
                "run_id", "mode", "status", "upper_bound_id", "cursor_id", "lease_token", "lease_until",
                "scanned_count", "prepared_count", "attached_count", "skipped_count", "failed_count",
                "created_at", "updated_at");
    }

    @Test
    void keyset_후보_조회는_기존_PK_또는_asset_인덱스로_범위를_제한한다() {
        List<Object[]> rows = new ArrayList<>();
        for (int index = 1; index <= 2_000; index++) {
            rows.add(new Object[]{"plan-" + index, "010%08d".formatted(index),
                    "plan-" + index + "@hashi.test", "profiles/plan.jpg"});
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO users (nickname, name_eng, birth_date, phone, email, profile_image_key,
                                   deleted, created_at, updated_at)
                VALUES (?, 'HASHI', '1998-01-01', ?, ?, ?, false, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, rows);
        jdbcTemplate.execute("ANALYZE TABLE users");
        long upper = candidateReader.findUpperBound();
        Map<String, Object> plan = jdbcTemplate.queryForMap("""
                EXPLAIN SELECT id, profile_image_key
                FROM users
                WHERE deleted = false AND profile_image_key IS NOT NULL AND profile_image_asset_id IS NULL
                  AND id > ? AND id <= ?
                ORDER BY id ASC LIMIT 50
                """, upper - 1_000, upper);

        assertThat(plan.get("type")).as("keyset query plan: %s", plan).isIn("range", "ref");
        assertThat(plan.get("key")).as("keyset query plan: %s", plan)
                .isIn("PRIMARY", "uq_users_profile_image_asset_id");
    }

    private Lease acquire(UUID runId, long upperBound) {
        Acquisition acquisition = checkpointStore.acquire(
                runId, UserProfileBackfillMode.ATTACH, upperBound, LEASE_DURATION);
        assertThat(acquisition.state()).isEqualTo(AcquisitionState.ACQUIRED);
        return acquisition.lease();
    }

    private void expire(Lease lease) {
        jdbcTemplate.update("""
                UPDATE user_profile_backfill_checkpoint
                SET lease_until = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6))
                WHERE run_id = ?
                """, lease.runId().toString());
    }

    private UserProfileBackfillCandidate candidate(User user) {
        return new UserProfileBackfillCandidate(user.getId(), user.getProfileImageKey());
    }

    private ImageAsset readyAsset() {
        UUID assetId = UUID.randomUUID();
        String identity = UUID.randomUUID().toString().replace("-", "").repeat(2);
        LocalDateTime now = LocalDateTime.now(clock);
        ImageAsset asset = ImageAsset.createSystemBackfill(assetId, MediaPurpose.PROFILE,
                "media/originals/%s/original".formatted(assetId), "image/jpeg", 1024L,
                now.plusMinutes(5), identity);
        UUID jobId = UUID.randomUUID();
        asset.beginInitialProcessing("version-1", "\"etag-1\"", 1, SPEC_DIGEST, jobId, now);
        asset.completeCurrentProcessing(
                jobId, 1, SPEC_DIGEST, "image/jpeg", 1024L, 3024, 4032, SOURCE_CHECKSUM);
        return imageAssetRepository.saveAndFlush(asset);
    }

    private MediaBackfillAssetInfo info(ImageAsset asset) {
        return new MediaBackfillAssetInfo(asset.getPublicId(), MediaAssetPurpose.PROFILE,
                asset.getBackfillIdentityHash(), MediaBackfillAssetInfo.State.READY);
    }

    private void assertBound(ImageAsset asset, ImageBindingStatus status) {
        assertThat(imageAssetRepository.findByPublicId(asset.getPublicId()).orElseThrow().getBindingStatus())
                .isEqualTo(status);
    }

    private User saveUser(String legacyKey) {
        String suffix = UUID.randomUUID().toString();
        return userRepository.saveAndFlush(User.onboard("profile-" + suffix, "HASHI",
                LocalDate.of(1998, 1, 1), suffix.substring(0, 20),
                suffix + "@hashi.test", legacyKey));
    }

    private Map<String, Object> userDetails(long userId) {
        return jdbcTemplate.queryForMap("""
                SELECT nickname, name_eng, birth_date, phone, email, profile_image_key, deleted, created_at
                FROM users WHERE id = ?
                """, userId);
    }

    private UserProfileBackfillOutcome raceAgainstOwnerWrite(
            User user, ImageAsset asset, Lease lease, Runnable ownerWrite
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
            Future<UserProfileBackfillOutcome> attach = executor.submit(() ->
                    attachmentService.attachAndRecord(candidate(user), info(asset), lease, LEASE_DURATION));
            try {
                awaitTableLockWait("users");
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
                    FROM user_profile_backfill_checkpoint WHERE run_id = ?
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
