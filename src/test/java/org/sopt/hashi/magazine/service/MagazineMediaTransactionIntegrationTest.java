package org.sopt.hashi.magazine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.sopt.hashi.auth.ActorType;
import org.sopt.hashi.auth.CurrentActor;
import org.sopt.hashi.auth.CurrentActorProvider;
import org.sopt.hashi.magazine.AdminMagazineCommand;
import org.sopt.hashi.magazine.AdminMagazineCommand.ImageCommand;
import org.sopt.hashi.magazine.MagazineInfo;
import org.sopt.hashi.magazine.domain.Magazine;
import org.sopt.hashi.magazine.domain.MagazineRepository;
import org.sopt.hashi.media.MediaImageRole;
import org.sopt.hashi.media.MediaImageStatus;
import org.sopt.hashi.media.code.MediaErrorCode;
import org.sopt.hashi.media.domain.ImageAsset;
import org.sopt.hashi.media.domain.ImageAssetRepository;
import org.sopt.hashi.media.domain.ImageBindingStatus;
import org.sopt.hashi.media.domain.ImageFormat;
import org.sopt.hashi.media.domain.ImageRole;
import org.sopt.hashi.media.domain.MediaOwnerType;
import org.sopt.hashi.media.domain.MediaPurpose;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 모듈 단위 테스트와 별개로 실제 magazine → media transaction 경계를 검증한다. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.properties.hibernate.session.events.log=false",
        "jwt.secret=test-secret-key-must-be-at-least-32-bytes-long",
        "kakao.client-id=test-client-id",
        "kakao.redirect-uri=https://app.hashi.test/callback",
        "hashi.storage.cloudfront-domain=https://cdn.hashi.test"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MagazineMediaTransactionIntegrationTest {

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
    private MagazineService magazineService;

    @Autowired
    private MagazineRepository magazineRepository;

    @Autowired
    private ImageAssetRepository imageAssetRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @MockitoBean
    private CurrentActorProvider currentActorProvider;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM magazine");
        jdbcTemplate.update("DELETE FROM image_rendition");
        jdbcTemplate.update("DELETE FROM image_asset");
        when(currentActorProvider.currentActor())
                .thenReturn(new CurrentActor(ActorType.ADMIN, 1L));
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void 등록은_두_asset과_매거진을_함께_commit하고_역할별_READY_응답을_만든다() {
        ImageAsset banner = asset(MediaPurpose.MAGAZINE_BANNER, 1L, true);
        ImageAsset thumbnail = asset(MediaPurpose.MAGAZINE_THUMBNAIL, 1L, true);

        MagazineInfo response = magazineService.create(createCommand(banner, thumbnail));

        assertImages(response.magazineId(), banner, thumbnail);
        assertBinding(banner, ImageBindingStatus.BOUND);
        assertBinding(thumbnail, ImageBindingStatus.BOUND);
        assertThat(response.bannerImage().status()).isEqualTo(MediaImageStatus.READY);
        assertThat(response.bannerImage().role()).isEqualTo(MediaImageRole.MAGAZINE_BANNER);
        assertThat(response.bannerImage().defaultSource().width()).isEqualTo(780);
        assertThat(response.thumbnailImage().role()).isEqualTo(MediaImageRole.MAGAZINE_THUMBNAIL);
        assertThat(response.thumbnailImage().defaultSource().width()).isEqualTo(312);
        assertThat(response.bannerImageUrl()).isEqualTo(response.bannerImage().defaultSource().url());
    }

    @Test
    void 두_슬롯_교체가_flush된_후_transaction이_실패하면_기존_소속과_binding을_복원한다() {
        ImageAsset oldBanner = asset(MediaPurpose.MAGAZINE_BANNER, 1L, true);
        ImageAsset oldThumbnail = asset(MediaPurpose.MAGAZINE_THUMBNAIL, 1L, true);
        Long magazineId = magazineService.create(createCommand(oldBanner, oldThumbnail)).magazineId();
        ImageAsset newBanner = asset(MediaPurpose.MAGAZINE_BANNER, 1L, true);
        ImageAsset newThumbnail = asset(MediaPurpose.MAGAZINE_THUMBNAIL, 1L, true);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            magazineService.update(magazineId, new AdminMagazineCommand(
                    "변경 제목", use(newBanner), use(newThumbnail), null));
            magazineRepository.flush();
            assertBinding(oldBanner, ImageBindingStatus.RETIRED);
            assertBinding(newBanner, ImageBindingStatus.BOUND);
            throw new IllegalStateException("commit before response failed");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("commit before response failed");

        assertImages(magazineId, oldBanner, oldThumbnail);
        assertThat(magazineRepository.findById(magazineId).orElseThrow().getTitle())
                .isEqualTo("이미지 매거진");
        assertBinding(oldBanner, ImageBindingStatus.BOUND);
        assertBinding(oldThumbnail, ImageBindingStatus.BOUND);
        assertBinding(newBanner, ImageBindingStatus.UNBOUND);
        assertBinding(newThumbnail, ImageBindingStatus.UNBOUND);
    }

    @Test
    void 한_슬롯이_PROCESSING이면_READY_슬롯도_claim하지_않고_기존_이미지를_유지한다() {
        ImageAsset oldBanner = asset(MediaPurpose.MAGAZINE_BANNER, 1L, true);
        ImageAsset oldThumbnail = asset(MediaPurpose.MAGAZINE_THUMBNAIL, 1L, true);
        Long magazineId = magazineService.create(createCommand(oldBanner, oldThumbnail)).magazineId();
        ImageAsset newBanner = asset(MediaPurpose.MAGAZINE_BANNER, 1L, true);
        ImageAsset processingThumbnail = asset(MediaPurpose.MAGAZINE_THUMBNAIL, 1L, false);

        assertThatThrownBy(() -> magazineService.update(magazineId, new AdminMagazineCommand(
                null, use(newBanner), use(processingThumbnail), null)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(MediaErrorCode.INVALID_STATE));

        assertImages(magazineId, oldBanner, oldThumbnail);
        assertBinding(oldBanner, ImageBindingStatus.BOUND);
        assertBinding(oldThumbnail, ImageBindingStatus.BOUND);
        assertBinding(newBanner, ImageBindingStatus.UNBOUND);
        assertBinding(processingThumbnail, ImageBindingStatus.UNBOUND);
    }

    @Test
    void 타인_asset은_존재를_숨기고_잘못된_purpose는_소속을_만들지_않는다() {
        ImageAsset banner = asset(MediaPurpose.MAGAZINE_BANNER, 1L, true);
        ImageAsset foreignThumbnail = asset(MediaPurpose.MAGAZINE_THUMBNAIL, 2L, true);
        ImageAsset wrongPurpose = asset(MediaPurpose.MAGAZINE_BANNER, 1L, true);

        assertThatThrownBy(() -> magazineService.create(createCommand(banner, foreignThumbnail)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(MediaErrorCode.ASSET_NOT_FOUND));
        assertThatThrownBy(() -> magazineService.create(createCommand(banner, wrongPurpose)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(MediaErrorCode.INVALID_STATE));

        assertThat(magazineRepository.count()).isZero();
        assertBinding(banner, ImageBindingStatus.UNBOUND);
        assertBinding(foreignThumbnail, ImageBindingStatus.UNBOUND);
        assertBinding(wrongPurpose, ImageBindingStatus.UNBOUND);
    }

    @ParameterizedTest
    @EnumSource(value = ImageBindingStatus.class, names = {"UNBOUND", "BOUND"})
    void 앞_배너의_상태가_잘못돼도_뒤_썸네일의_존재는_드러내지_않는다(ImageBindingStatus bindingStatus) {
        ImageAsset oldBanner = asset(MediaPurpose.MAGAZINE_BANNER, 1L, true);
        ImageAsset oldThumbnail = asset(MediaPurpose.MAGAZINE_THUMBNAIL, 1L, true);
        Long magazineId = magazineService.create(createCommand(oldBanner, oldThumbnail)).magazineId();
        ImageAsset banner = asset(
                MediaPurpose.MAGAZINE_BANNER, 1L, bindingStatus == ImageBindingStatus.BOUND);
        if (bindingStatus == ImageBindingStatus.BOUND) {
            banner.bind();
            imageAssetRepository.saveAndFlush(banner);
        }
        ImageAsset foreignThumbnail = asset(MediaPurpose.MAGAZINE_THUMBNAIL, 2L, true);

        for (UUID deniedId : List.of(foreignThumbnail.getPublicId(), UUID.randomUUID())) {
            AdminMagazineCommand command = new AdminMagazineCommand(
                    "저장되면 안 되는 제목", use(banner), new ImageCommand(null, deniedId),
                    "https://www.instagram.com/p/denied-media/");

            assertThatThrownBy(() -> magazineService.create(command))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.getErrorCode())
                                    .isEqualTo(MediaErrorCode.ASSET_NOT_FOUND));
            assertThatThrownBy(() -> magazineService.update(magazineId, command))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.getErrorCode())
                                    .isEqualTo(MediaErrorCode.ASSET_NOT_FOUND));

            assertThat(magazineRepository.count()).isEqualTo(1L);
            assertImages(magazineId, oldBanner, oldThumbnail);
            assertThat(magazineRepository.findById(magazineId).orElseThrow().getTitle())
                    .isEqualTo("이미지 매거진");
            assertBinding(oldBanner, ImageBindingStatus.BOUND);
            assertBinding(oldThumbnail, ImageBindingStatus.BOUND);
            assertBinding(banner, bindingStatus);
            assertBinding(foreignThumbnail, ImageBindingStatus.UNBOUND);
        }
    }

    @Test
    void 동일_asset_PATCH는_재claim하지_않고_soft_delete는_binding을_유지한다() {
        ImageAsset banner = asset(MediaPurpose.MAGAZINE_BANNER, 1L, true);
        ImageAsset thumbnail = asset(MediaPurpose.MAGAZINE_THUMBNAIL, 1L, true);
        Long magazineId = magazineService.create(createCommand(banner, thumbnail)).magazineId();

        magazineService.update(magazineId, new AdminMagazineCommand(
                null, use(banner), use(thumbnail), null));
        assertImages(magazineId, banner, thumbnail);
        magazineService.delete(magazineId);

        assertThat(magazineRepository.findById(magazineId)).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted FROM magazine WHERE id = ?", Boolean.class, magazineId)).isTrue();
        assertBinding(banner, ImageBindingStatus.BOUND);
        assertBinding(thumbnail, ImageBindingStatus.BOUND);
    }

    @Test
    void 동시_배너_교체는_행잠금으로_직렬화하고_마지막_asset만_BOUND로_남긴다() throws Exception {
        ImageAsset oldBanner = asset(MediaPurpose.MAGAZINE_BANNER, 1L, true);
        ImageAsset thumbnail = asset(MediaPurpose.MAGAZINE_THUMBNAIL, 1L, true);
        Long magazineId = magazineService.create(createCommand(oldBanner, thumbnail)).magazineId();
        ImageAsset firstBanner = asset(MediaPurpose.MAGAZINE_BANNER, 1L, true);
        ImageAsset secondBanner = asset(MediaPurpose.MAGAZINE_BANNER, 1L, true);
        CountDownLatch firstFlushed = new CountDownLatch(1);
        CountDownLatch allowFirstCommit = new CountDownLatch(1);

        Future<?> first = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            magazineService.update(magazineId, new AdminMagazineCommand(
                    null, use(firstBanner), null, null));
            magazineRepository.flush();
            firstFlushed.countDown();
            await(allowFirstCommit);
        }));
        assertThat(firstFlushed.await(10, TimeUnit.SECONDS)).isTrue();
        Future<?> second = executor.submit(() -> magazineService.update(
                magazineId, new AdminMagazineCommand(null, use(secondBanner), null, null)));
        try {
            awaitMagazineRowLockCompetition();
        } finally {
            allowFirstCommit.countDown();
        }
        first.get(20, TimeUnit.SECONDS);
        second.get(20, TimeUnit.SECONDS);

        assertImages(magazineId, secondBanner, thumbnail);
        assertBinding(oldBanner, ImageBindingStatus.RETIRED);
        assertBinding(firstBanner, ImageBindingStatus.RETIRED);
        assertBinding(secondBanner, ImageBindingStatus.BOUND);
        assertBinding(thumbnail, ImageBindingStatus.BOUND);
    }

    @Test
    void 삭제는_진행중인_교체의_commit을_기다려_이전_이미지로_되돌리지_않는다() throws Exception {
        ImageAsset oldBanner = asset(MediaPurpose.MAGAZINE_BANNER, 1L, true);
        ImageAsset thumbnail = asset(MediaPurpose.MAGAZINE_THUMBNAIL, 1L, true);
        Long magazineId = magazineService.create(createCommand(oldBanner, thumbnail)).magazineId();
        ImageAsset newBanner = asset(MediaPurpose.MAGAZINE_BANNER, 1L, true);
        CountDownLatch updateFlushed = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);

        Future<?> update = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            magazineService.update(magazineId, new AdminMagazineCommand(
                    null, use(newBanner), null, null));
            magazineRepository.flush();
            updateFlushed.countDown();
            await(allowCommit);
        }));
        assertThat(updateFlushed.await(10, TimeUnit.SECONDS)).isTrue();
        Future<?> delete = executor.submit(() -> magazineService.delete(magazineId));
        try {
            awaitMagazineRowLockCompetition();
        } finally {
            allowCommit.countDown();
        }
        update.get(20, TimeUnit.SECONDS);
        delete.get(20, TimeUnit.SECONDS);

        assertThat(magazineRepository.findById(magazineId)).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT banner_image_asset_id FROM magazine WHERE id = ?", String.class, magazineId))
                .isEqualTo(newBanner.getPublicId().toString());
        assertBinding(oldBanner, ImageBindingStatus.RETIRED);
        assertBinding(newBanner, ImageBindingStatus.BOUND);
        assertBinding(thumbnail, ImageBindingStatus.BOUND);
    }

    @Test
    void 목록_조회는_매거진_한개나_열개나_SQL_세번으로_파생본을_일괄_조회한다() {
        for (int index = 0; index < 10; index++) {
            ImageAsset banner = asset(MediaPurpose.MAGAZINE_BANNER, 1L, true);
            ImageAsset thumbnail = asset(MediaPurpose.MAGAZINE_THUMBNAIL, 1L, true);
            magazineService.create(createCommand(banner, thumbnail));
        }
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        assertThat(magazineService.getMagazines(null, 1).magazines()).hasSize(1);
        long singleCount = statistics.getPrepareStatementCount();
        statistics.clear();
        assertThat(magazineService.getMagazines(null, 10).magazines()).hasSize(10);
        long manyCount = statistics.getPrepareStatementCount();

        assertThat(singleCount).isEqualTo(3L);
        assertThat(manyCount).isEqualTo(3L);
    }

    private ImageAsset asset(MediaPurpose purpose, Long ownerId, boolean ready) {
        UUID assetId = UUID.randomUUID();
        ImageAsset asset = ImageAsset.createDirectUpload(
                assetId, purpose, MediaOwnerType.ADMIN, ownerId,
                "media/originals/%s/original".formatted(assetId),
                "image/jpeg", 1024L, LocalDateTime.now().plusMinutes(5));
        UUID jobId = UUID.randomUUID();
        asset.beginInitialProcessing(
                "version-1", "\"etag-1\"", 1, SPEC_DIGEST, jobId, LocalDateTime.now());
        if (ready) {
            ImageRole role = ImageRole.valueOf(purpose.name());
            int[][] sizes = purpose == MediaPurpose.MAGAZINE_BANNER
                    ? new int[][]{{390, 177}, {780, 354}, {1170, 530}}
                    : new int[][]{{156, 88}, {312, 176}, {468, 264}};
            for (int[] size : sizes) {
                asset.addRendition(
                        jobId, 1, SPEC_DIGEST, role, ImageFormat.WEBP,
                        size[0], size[1], 100L,
                        "media/renditions/%s/v1/%s/%d.webp".formatted(
                                assetId, role.name().toLowerCase(), size[0]));
            }
            asset.completeCurrentProcessing(
                    jobId, 1, SPEC_DIGEST, "image/jpeg", 1024L, 3024, 4032, SOURCE_CHECKSUM);
        }
        return imageAssetRepository.saveAndFlush(asset);
    }

    private AdminMagazineCommand createCommand(ImageAsset banner, ImageAsset thumbnail) {
        return new AdminMagazineCommand(
                "이미지 매거진", use(banner), use(thumbnail), "https://www.instagram.com/p/media-test/");
    }

    private ImageCommand use(ImageAsset asset) {
        return new ImageCommand(null, asset.getPublicId());
    }

    private void assertImages(Long magazineId, ImageAsset banner, ImageAsset thumbnail) {
        Magazine magazine = magazineRepository.findById(magazineId).orElseThrow();
        assertThat(magazine.getBannerKey()).isNull();
        assertThat(magazine.getThumbnailKey()).isNull();
        assertThat(magazine.getBannerImageAssetId()).isEqualTo(banner.getPublicId());
        assertThat(magazine.getThumbnailImageAssetId()).isEqualTo(thumbnail.getPublicId());
    }

    private void assertBinding(ImageAsset asset, ImageBindingStatus expected) {
        assertThat(imageAssetRepository.findByPublicId(asset.getPublicId()).orElseThrow()
                .getBindingStatus()).isEqualTo(expected);
    }

    private void awaitMagazineRowLockCompetition() throws SQLException, InterruptedException {
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                try (ResultSet rows = statement.executeQuery("""
                        SELECT COUNT(*)
                        FROM performance_schema.data_lock_waits waits
                        JOIN performance_schema.data_locks locks
                          ON locks.engine_lock_id = waits.requesting_engine_lock_id
                         AND locks.engine = waits.engine
                        WHERE locks.object_schema = DATABASE()
                          AND locks.object_name = 'magazine'
                        """)) {
                    rows.next();
                    if (rows.getInt(1) > 0) {
                        return;
                    }
                }
                Thread.sleep(50);
            }
        }
        throw new AssertionError("MySQL magazine 행잠금 경쟁을 관찰하지 못했습니다");
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("매거진 transaction commit 대기 시간 초과");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("매거진 transaction 대기 중단", exception);
        }
    }
}
