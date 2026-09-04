package org.sopt.hashi.restaurant.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
import org.sopt.hashi.auth.ActorType;
import org.sopt.hashi.auth.CurrentActor;
import org.sopt.hashi.auth.CurrentActorProvider;
import org.sopt.hashi.media.MediaAssetPurpose;
import org.sopt.hashi.media.MediaBackfillAssetInfo;
import org.sopt.hashi.media.domain.ImageAsset;
import org.sopt.hashi.media.domain.ImageAssetRepository;
import org.sopt.hashi.media.domain.ImageBindingStatus;
import org.sopt.hashi.media.domain.MediaPurpose;
import org.sopt.hashi.media.internal.backfill.MediaBackfillStorage;
import org.sopt.hashi.restaurant.AdminRestaurantCommand;
import org.sopt.hashi.restaurant.AdminRestaurantCommand.MenuCommand;
import org.sopt.hashi.restaurant.domain.PriceCurrency;
import org.sopt.hashi.restaurant.domain.Restaurant;
import org.sopt.hashi.restaurant.domain.RestaurantGenre;
import org.sopt.hashi.restaurant.domain.RestaurantImage;
import org.sopt.hashi.restaurant.domain.RestaurantMenu;
import org.sopt.hashi.restaurant.domain.RestaurantRepository;
import org.sopt.hashi.restaurant.migration.RestaurantMediaBackfillCheckpointStore.Acquisition;
import org.sopt.hashi.restaurant.migration.RestaurantMediaBackfillCheckpointStore.AcquisitionState;
import org.sopt.hashi.restaurant.migration.RestaurantMediaBackfillCheckpointStore.Lease;
import org.sopt.hashi.restaurant.migration.RestaurantMediaBackfillCheckpointStore.Snapshot;
import org.sopt.hashi.restaurant.service.RestaurantService;
import org.springframework.beans.factory.annotation.Autowired;
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

/** 실제 media claim과 식당 association·checkpoint의 교차 transaction을 검증하는 전체 통합 gate. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "jwt.secret=test-secret-key-must-be-at-least-32-bytes-long",
        "kakao.client-id=test-client-id",
        "kakao.redirect-uri=https://app.hashi.test/callback",
        "hashi.storage.cloudfront-domain=https://cdn.hashi.test",
        "hashi.media.backfill.enabled=true",
        "hashi.restaurant.media-backfill.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RestaurantMediaBackfillPersistenceIntegrationTest {

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
    private RestaurantMediaBackfillCheckpointStore checkpointStore;
    @Autowired
    private RestaurantMediaBackfillCandidateReader candidateReader;
    @Autowired
    private RestaurantMediaBackfillAttachmentService attachmentService;
    @Autowired
    private RestaurantRepository restaurantRepository;
    @Autowired
    private RestaurantService restaurantService;
    @Autowired
    private ImageAssetRepository imageAssetRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockitoBean
    private CurrentActorProvider currentActorProvider;
    @MockitoBean
    private MediaBackfillStorage mediaBackfillStorage;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM restaurant_media_backfill_checkpoint");
        jdbcTemplate.update("DELETE FROM restaurant_image");
        jdbcTemplate.update("DELETE FROM restaurant_menu");
        jdbcTemplate.update("DELETE FROM restaurant_business_hour");
        jdbcTemplate.update("DELETE FROM restaurant_hashtag");
        jdbcTemplate.update("DELETE FROM restaurant_curation_type");
        jdbcTemplate.update("DELETE FROM restaurant");
        jdbcTemplate.update("DELETE FROM image_rendition");
        jdbcTemplate.update("DELETE FROM image_asset");
        given(currentActorProvider.currentActor()).willReturn(new CurrentActor(ActorType.ADMIN, 1L));
    }

    @Test
    void 같은_run_ID는_상한과_cursor를_보존하며_한_worker만_lease를_얻는다() {
        UUID runId = UUID.randomUUID();
        Acquisition first = checkpointStore.acquire(
                runId, RestaurantMediaBackfillTarget.RESTAURANT_IMAGE,
                RestaurantMediaBackfillMode.PREPARE, 100L, LEASE_DURATION);
        Acquisition busy = checkpointStore.acquire(
                runId, RestaurantMediaBackfillTarget.RESTAURANT_IMAGE,
                RestaurantMediaBackfillMode.PREPARE, 200L, LEASE_DURATION);

        assertThat(first.state()).isEqualTo(AcquisitionState.ACQUIRED);
        assertThat(busy.state()).isEqualTo(AcquisitionState.BUSY);
        checkpointStore.recordProgress(
                first.lease(), 10L, RestaurantMediaBackfillOutcome.PREPARED, LEASE_DURATION);
        assertThat(checkpointStore.pause(first.lease())).isTrue();

        Acquisition resumed = checkpointStore.acquire(
                runId, RestaurantMediaBackfillTarget.RESTAURANT_IMAGE,
                RestaurantMediaBackfillMode.PREPARE, 200L, LEASE_DURATION);

        assertThat(resumed.state()).isEqualTo(AcquisitionState.ACQUIRED);
        assertThat(resumed.snapshot().upperBoundId()).isEqualTo(100L);
        assertThat(resumed.snapshot().cursorId()).isEqualTo(10L);
        assertThat(resumed.snapshot().preparedCount()).isEqualTo(1L);
        assertThat(resumed.lease().token()).isNotEqualTo(first.lease().token());
    }

    @Test
    void 만료된_lease의_worker는_새_worker의_cursor를_갱신하거나_중지할_수_없다() {
        UUID runId = UUID.randomUUID();
        Lease first = acquire(runId, RestaurantMediaBackfillTarget.RESTAURANT_IMAGE, 100L);
        expire(first);
        Lease replacement = acquire(runId, RestaurantMediaBackfillTarget.RESTAURANT_IMAGE, 100L);

        assertThatThrownBy(() -> checkpointStore.recordProgress(
                first, 10L, RestaurantMediaBackfillOutcome.ATTACHED, LEASE_DURATION))
                .isInstanceOf(RestaurantMediaBackfillLeaseLostException.class);
        assertThat(checkpointStore.pause(first)).isFalse();
        checkpointStore.recordProgress(
                replacement, 20L, RestaurantMediaBackfillOutcome.SKIPPED, LEASE_DURATION);

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
                runId, RestaurantMediaBackfillTarget.RESTAURANT_IMAGE,
                RestaurantMediaBackfillMode.ATTACH, 10L, Duration.ofSeconds(3)).lease();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT run_id FROM restaurant_media_backfill_checkpoint WHERE run_id = ? FOR UPDATE
                    """)) {
                statement.setString(1, runId.toString());
                try (ResultSet ignored = statement.executeQuery()) {
                    assertThat(ignored.next()).isTrue();
                }
            }
            Future<?> progress = executor.submit(() -> checkpointStore.recordProgress(
                    lease, 1L, RestaurantMediaBackfillOutcome.SKIPPED, LEASE_DURATION));
            try {
                awaitTableLockWait("restaurant_media_backfill_checkpoint");
                awaitLeaseExpiry(runId);
            } finally {
                connection.commit();
            }

            assertThatThrownBy(() -> progress.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(RestaurantMediaBackfillLeaseLostException.class);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertThat(checkpointStore.find(runId).scannedCount()).isZero();
        assertThat(checkpointStore.find(runId).cursorId()).isZero();
    }

    @Test
    void run_ID의_target과_mode를_재사용해_바꾸지_않는다() {
        UUID runId = UUID.randomUUID();
        Lease lease = acquire(runId, RestaurantMediaBackfillTarget.RESTAURANT_IMAGE, 10L);
        checkpointStore.pause(lease);
        Snapshot before = checkpointStore.find(runId);

        assertThatThrownBy(() -> checkpointStore.acquire(
                runId, RestaurantMediaBackfillTarget.RESTAURANT_MENU,
                RestaurantMediaBackfillMode.ATTACH, 10L, LEASE_DURATION))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasRootCauseExactlyInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage("runId is already assigned to a different backfill execution");
        assertThat(checkpointStore.find(runId)).isEqualTo(before);
        assertThatThrownBy(() -> checkpointStore.acquire(
                runId, RestaurantMediaBackfillTarget.RESTAURANT_IMAGE,
                RestaurantMediaBackfillMode.PREPARE, 10L, LEASE_DURATION))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasRootCauseExactlyInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage("runId is already assigned to a different backfill execution");
        assertThat(checkpointStore.find(runId)).isEqualTo(before);
    }

    @Test
    void 완료된_run_ID는_새_상한이_생겨도_다시_실행하지_않는다() {
        UUID runId = UUID.randomUUID();
        Lease lease = acquire(runId, RestaurantMediaBackfillTarget.RESTAURANT_IMAGE, 10L);
        checkpointStore.complete(lease);

        Acquisition completed = checkpointStore.acquire(
                runId, RestaurantMediaBackfillTarget.RESTAURANT_IMAGE,
                RestaurantMediaBackfillMode.ATTACH, 20L, LEASE_DURATION);

        assertThat(completed.state()).isEqualTo(AcquisitionState.COMPLETED);
        assertThat(completed.snapshot().upperBoundId()).isEqualTo(10L);
        assertThat(completed.snapshot().cursorId()).isEqualTo(10L);
    }

    @Test
    void V22는_잘못된_mode_cursor_lease와_집계값을_DB에서도_거부한다() {
        UUID runId = UUID.randomUUID();
        Lease lease = acquire(runId, RestaurantMediaBackfillTarget.RESTAURANT_IMAGE, 10L);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE restaurant_media_backfill_checkpoint SET mode = 'DRY_RUN' WHERE run_id = ?",
                runId.toString())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE restaurant_media_backfill_checkpoint SET cursor_id = 11 WHERE run_id = ?",
                runId.toString())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE restaurant_media_backfill_checkpoint SET lease_token = NULL WHERE run_id = ?",
                runId.toString())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE restaurant_media_backfill_checkpoint SET attached_count = 1 WHERE run_id = ?",
                runId.toString())).isInstanceOf(DataAccessException.class);
        assertThat(checkpointStore.pause(lease)).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.key_column_usage
                WHERE constraint_schema = DATABASE()
                  AND table_name = 'restaurant_media_backfill_checkpoint'
                  AND referenced_table_name IS NOT NULL
                """, Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT engine FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name = 'restaurant_media_backfill_checkpoint'
                """, String.class)).isEqualToIgnoringCase("InnoDB");
    }

    @Test
    void keyset은_삭제된_식당을_포함하고_고정_상한_이후_신규_이미지는_제외한다() {
        Restaurant active = restaurant("활성 식당");
        active.replaceImages(List.of(
                RestaurantImage.createLegacy("restaurants/first.jpg", 1),
                RestaurantImage.createAsset(UUID.randomUUID(), 2)));
        restaurantRepository.saveAndFlush(active);
        Restaurant deleted = restaurant("삭제 식당");
        deleted.softDelete();
        deleted.replaceImages(List.of(RestaurantImage.createLegacy("restaurants/deleted.jpg", 1)));
        restaurantRepository.saveAndFlush(deleted);
        long upperBound = candidateReader.findUpperBound(RestaurantMediaBackfillTarget.RESTAURANT_IMAGE);

        Restaurant newer = restaurant("상한 이후 식당");
        newer.replaceImages(List.of(RestaurantImage.createLegacy("restaurants/new.jpg", 1)));
        restaurantRepository.saveAndFlush(newer);

        List<RestaurantMediaBackfillCandidate> first = candidateReader.findBatch(
                RestaurantMediaBackfillTarget.RESTAURANT_IMAGE, 0L, upperBound, 1);
        List<RestaurantMediaBackfillCandidate> second = candidateReader.findBatch(
                RestaurantMediaBackfillTarget.RESTAURANT_IMAGE,
                first.getFirst().associationId(), upperBound, 10);

        assertThat(first).extracting(RestaurantMediaBackfillCandidate::associationId)
                .containsExactly(active.getImages().getFirst().getId());
        assertThat(second).extracting(RestaurantMediaBackfillCandidate::associationId)
                .containsExactly(deleted.getImages().getFirst().getId());
        assertThat(second.getFirst().restaurantId()).isEqualTo(deleted.getId());
    }

    @Test
    void READY_식당_이미지_연결은_legacy_key_순서_삭제상태와_집계를_보존한다() {
        Restaurant restaurant = restaurant("삭제된 backfill 식당");
        restaurant.softDelete();
        restaurant.replaceImages(List.of(RestaurantImage.createLegacy("restaurants/legacy.jpg", 3)));
        restaurantRepository.saveAndFlush(restaurant);
        RestaurantImage image = restaurant.getImages().getFirst();
        ImageAsset asset = readyAsset(MediaPurpose.RESTAURANT);
        Lease lease = acquire(
                UUID.randomUUID(), RestaurantMediaBackfillTarget.RESTAURANT_IMAGE, image.getId());

        RestaurantMediaBackfillOutcome outcome = attachmentService.attachAndRecord(
                candidate(restaurant, image), info(asset), lease, LEASE_DURATION);

        assertThat(outcome).isEqualTo(RestaurantMediaBackfillOutcome.ATTACHED);
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT file_key, image_asset_id, display_order FROM restaurant_image WHERE id = ?
                """, image.getId());
        assertThat(row).containsEntry("file_key", "restaurants/legacy.jpg")
                .containsEntry("image_asset_id", asset.getPublicId().toString())
                .containsEntry("display_order", 3);
        assertThat(restaurantRepository.findById(restaurant.getId()).orElseThrow().isDeleted()).isTrue();
        assertThat(imageAssetRepository.findByPublicId(asset.getPublicId()).orElseThrow().getBindingStatus())
                .isEqualTo(ImageBindingStatus.BOUND);
        assertThat(checkpointStore.find(lease.runId()).attachedCount()).isEqualTo(1L);
    }

    @Test
    void lease_fence_실패는_media_claim_association_cursor를_모두_rollback한다() {
        Restaurant restaurant = restaurant("rollback 식당");
        restaurant.replaceImages(List.of(RestaurantImage.createLegacy("restaurants/rollback.jpg", 1)));
        restaurantRepository.saveAndFlush(restaurant);
        RestaurantImage image = restaurant.getImages().getFirst();
        ImageAsset asset = readyAsset(MediaPurpose.RESTAURANT);
        Lease lease = acquire(
                UUID.randomUUID(), RestaurantMediaBackfillTarget.RESTAURANT_IMAGE, image.getId());
        expire(lease);

        assertThatThrownBy(() -> attachmentService.attachAndRecord(
                candidate(restaurant, image), info(asset), lease, LEASE_DURATION))
                .isInstanceOf(RestaurantMediaBackfillLeaseLostException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT image_asset_id FROM restaurant_image WHERE id = ?", String.class, image.getId()))
                .isNull();
        assertThat(imageAssetRepository.findByPublicId(asset.getPublicId()).orElseThrow().getBindingStatus())
                .isEqualTo(ImageBindingStatus.UNBOUND);
        Snapshot snapshot = checkpointStore.find(lease.runId());
        assertThat(snapshot.cursorId()).isZero();
        assertThat(snapshot.scannedCount()).isZero();
    }

    @Test
    void READY_메뉴_연결은_가격_설명_main과_legacy_key를_변경하지_않는다() {
        Restaurant restaurant = restaurant("메뉴 보존 식당");
        RestaurantMenu menu = RestaurantMenu.create(
                "기존 메뉴", "기존 설명", "menus/legacy.jpg", PriceCurrency.JPY,
                BigDecimal.valueOf(1_500), true);
        restaurant.replaceMenus(List.of(menu));
        restaurantRepository.saveAndFlush(restaurant);
        ImageAsset asset = readyAsset(MediaPurpose.RESTAURANT_MENU);
        Lease lease = acquire(
                UUID.randomUUID(), RestaurantMediaBackfillTarget.RESTAURANT_MENU, menu.getId());

        RestaurantMediaBackfillOutcome outcome = attachmentService.attachAndRecord(
                new RestaurantMediaBackfillCandidate(
                        RestaurantMediaBackfillTarget.RESTAURANT_MENU,
                        menu.getId(), restaurant.getId(), "menus/legacy.jpg"),
                info(asset), lease, LEASE_DURATION);

        assertThat(outcome).isEqualTo(RestaurantMediaBackfillOutcome.ATTACHED);
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT name, description, image_key, image_asset_id, price_currency, price_amount, is_main
                FROM restaurant_menu WHERE id = ?
                """, menu.getId());
        assertThat(row).containsEntry("name", "기존 메뉴")
                .containsEntry("description", "기존 설명")
                .containsEntry("image_key", "menus/legacy.jpg")
                .containsEntry("image_asset_id", asset.getPublicId().toString())
                .containsEntry("price_currency", "JPY");
        assertThat((BigDecimal) row.get("price_amount")).isEqualByComparingTo("1500.00");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT is_main FROM restaurant_menu WHERE id = ?", Boolean.class, menu.getId())).isTrue();
    }

    @Test
    void 관리자_메뉴_수정이_먼저_잠그면_attach는_변경된_source를_덮어쓰지_않는다() throws Exception {
        Restaurant restaurant = restaurant("동시 메뉴 식당");
        RestaurantMenu menu = RestaurantMenu.create(
                "기존 메뉴", "기존 설명", "menus/old.jpg", PriceCurrency.JPY,
                BigDecimal.valueOf(1_000), true);
        restaurant.replaceMenus(List.of(menu));
        restaurantRepository.saveAndFlush(restaurant);
        ImageAsset asset = readyAsset(MediaPurpose.RESTAURANT_MENU);
        Lease lease = acquire(
                UUID.randomUUID(), RestaurantMediaBackfillTarget.RESTAURANT_MENU, menu.getId());
        RestaurantMediaBackfillCandidate stale = new RestaurantMediaBackfillCandidate(
                RestaurantMediaBackfillTarget.RESTAURANT_MENU,
                menu.getId(), restaurant.getId(), "menus/old.jpg");
        CountDownLatch changed = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> update = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                restaurantService.updateByAdmin(restaurant.getId(), menuUpdate(menu.getId()));
                changed.countDown();
                await(allowCommit);
            }));
            assertThat(changed.await(10, TimeUnit.SECONDS)).isTrue();
            Future<RestaurantMediaBackfillOutcome> attach = executor.submit(() ->
                    attachmentService.attachAndRecord(stale, info(asset), lease, LEASE_DURATION));
            try {
                awaitTableLockWait("restaurant");
            } finally {
                allowCommit.countDown();
            }

            update.get(20, TimeUnit.SECONDS);
            assertThat(attach.get(20, TimeUnit.SECONDS)).isEqualTo(RestaurantMediaBackfillOutcome.SKIPPED);
        } finally {
            allowCommit.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertThat(jdbcTemplate.queryForMap("""
                SELECT name, description, image_key, image_asset_id, price_amount, is_main
                FROM restaurant_menu WHERE id = ?
                """, menu.getId()))
                .containsEntry("name", "변경 메뉴")
                .containsEntry("description", "변경 설명")
                .containsEntry("image_key", "menus/new.jpg")
                .containsEntry("image_asset_id", null);
        assertThat(imageAssetRepository.findByPublicId(asset.getPublicId()).orElseThrow().getBindingStatus())
                .isEqualTo(ImageBindingStatus.UNBOUND);
        assertThat(checkpointStore.find(lease.runId()).skippedCount()).isEqualTo(1L);
    }

    @Test
    void keyset_후보_조회는_PK_범위_또는_asset_인덱스를_사용한다() {
        Restaurant restaurant = restaurantRepository.saveAndFlush(restaurant("query plan 식당"));
        List<Object[]> rows = new ArrayList<>();
        for (int index = 1; index <= 2_000; index++) {
            rows.add(new Object[]{restaurant.getId(), "restaurants/plan.jpg", index});
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO restaurant_image (
                    restaurant_id, file_key, display_order, created_at, updated_at
                ) VALUES (?, ?, ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, rows);
        jdbcTemplate.execute("ANALYZE TABLE restaurant_image");
        long upper = candidateReader.findUpperBound(RestaurantMediaBackfillTarget.RESTAURANT_IMAGE);
        Map<String, Object> plan = jdbcTemplate.queryForMap("""
                EXPLAIN SELECT id, restaurant_id, file_key
                FROM restaurant_image
                WHERE file_key IS NOT NULL AND image_asset_id IS NULL
                  AND id > ? AND id <= ?
                ORDER BY id ASC LIMIT 50
                """, upper - 1_000, upper);

        assertThat(plan.get("type")).as("keyset query plan: %s", plan).isIn("range", "ref");
        assertThat(plan.get("key")).as("keyset query plan: %s", plan)
                .isIn("PRIMARY", "uq_restaurant_image_asset_id");
    }

    private Lease acquire(UUID runId, RestaurantMediaBackfillTarget target, long upperBound) {
        Acquisition acquisition = checkpointStore.acquire(
                runId, target, RestaurantMediaBackfillMode.ATTACH, upperBound, LEASE_DURATION);
        assertThat(acquisition.state()).isEqualTo(AcquisitionState.ACQUIRED);
        return acquisition.lease();
    }

    private void expire(Lease lease) {
        jdbcTemplate.update("""
                UPDATE restaurant_media_backfill_checkpoint
                SET lease_until = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6))
                WHERE run_id = ?
                """, lease.runId().toString());
    }

    private RestaurantMediaBackfillCandidate candidate(Restaurant restaurant, RestaurantImage image) {
        return new RestaurantMediaBackfillCandidate(
                RestaurantMediaBackfillTarget.RESTAURANT_IMAGE,
                image.getId(), restaurant.getId(), image.getFileKey());
    }

    private ImageAsset readyAsset(MediaPurpose purpose) {
        UUID assetId = UUID.randomUUID();
        String identity = UUID.randomUUID().toString().replace("-", "").repeat(2);
        ImageAsset asset = ImageAsset.createSystemBackfill(
                assetId, purpose, "media/originals/%s/original".formatted(assetId),
                "image/jpeg", 1024L, LocalDateTime.now().plusMinutes(5), identity);
        UUID jobId = UUID.randomUUID();
        asset.beginInitialProcessing(
                "version-1", "\"etag-1\"", 1, SPEC_DIGEST, jobId, LocalDateTime.now());
        asset.completeCurrentProcessing(
                jobId, 1, SPEC_DIGEST, "image/jpeg", 1024L, 3024, 4032, SOURCE_CHECKSUM);
        return imageAssetRepository.saveAndFlush(asset);
    }

    private MediaBackfillAssetInfo info(ImageAsset asset) {
        return new MediaBackfillAssetInfo(
                asset.getPublicId(), MediaAssetPurpose.valueOf(asset.getPurpose().name()),
                asset.getBackfillIdentityHash(), MediaBackfillAssetInfo.State.READY);
    }

    private Restaurant restaurant(String name) {
        return Restaurant.create(
                name, "backfill restaurant", "소개", "상세 설명",
                "도쿄도", "도쿄", RestaurantGenre.SUSHI, "초밥",
                PriceCurrency.JPY, BigDecimal.valueOf(1_000), BigDecimal.valueOf(2_000));
    }

    private AdminRestaurantCommand menuUpdate(Long menuId) {
        return new AdminRestaurantCommand(
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null,
                List.of(new MenuCommand(
                        menuId, "변경 메뉴", "변경 설명", "menus/new.jpg", null,
                        "JPY", BigDecimal.valueOf(2_000), false)),
                null, null, null);
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
                    FROM restaurant_media_backfill_checkpoint WHERE run_id = ?
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
