package org.sopt.hashi.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.auth.ActorType;
import org.sopt.hashi.auth.CurrentActor;
import org.sopt.hashi.auth.CurrentActorProvider;
import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.media.domain.ImageAsset;
import org.sopt.hashi.media.domain.ImageAssetRepository;
import org.sopt.hashi.media.domain.ImageBindingStatus;
import org.sopt.hashi.media.domain.MediaOwnerType;
import org.sopt.hashi.media.domain.MediaPurpose;
import org.sopt.hashi.user.domain.User;
import org.sopt.hashi.user.domain.UserRepository;
import org.sopt.hashi.user.dto.UpdateMyInfoRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 같은 회원의 사진 교체 요청이 겹칠 때 — 회원 행 잠금이 없으면 둘 다 "현재 사진 없음"으로 읽어 각자 claim만 하고,
 * 먼저 연결한 asset은 어디에도 참조되지 않은 채 BOUND로 남는다. 잠금이 두 요청을 순서대로 처리해 앞 asset을 retire하는지 본다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "jwt.secret=test-secret-key-must-be-at-least-32-bytes-long",
        "kakao.client-id=test-client-id",
        "kakao.redirect-uri=https://app.hashi.test/callback",
        "hashi.storage.cloudfront-domain=https://cdn.hashi.test"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UserProfileImageConcurrencyIntegrationTest {

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
    private UserProfileService userProfileService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ImageAssetRepository imageAssetRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @MockitoBean
    private CurrentActorProvider currentActorProvider;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void 같은_회원의_사진_교체가_겹치면_먼저_연결한_asset을_뒤_요청이_retire한다() throws Exception {
        User user = userRepository.saveAndFlush(User.onboard(
                "동시수정회원", "HASHI", LocalDate.of(1998, 1, 1),
                "01000000241", "concurrent@hashi.test", null, null));
        Long userId = user.getId();
        when(currentUserProvider.currentUserId()).thenReturn(userId);
        when(currentActorProvider.currentActor()).thenReturn(new CurrentActor(ActorType.USER, userId));
        ImageAsset firstAsset = profileAsset(userId);
        ImageAsset secondAsset = profileAsset(userId);
        transactionTemplate.executeWithoutResult(status ->
                imageAssetRepository.saveAll(List.of(firstAsset, secondAsset)));
        CountDownLatch firstFlushed = new CountDownLatch(1);
        CountDownLatch allowFirstCommit = new CountDownLatch(1);

        Future<?> first = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            userProfileService.updateMyInfo(imageOnly(firstAsset.getPublicId()));
            firstFlushed.countDown();
            await(allowFirstCommit, "첫 사진 교체 transaction commit");
        }));
        assertThat(firstFlushed.await(10, TimeUnit.SECONDS)).isTrue();

        Future<?> second = executor.submit(() -> userProfileService.updateMyInfo(imageOnly(secondAsset.getPublicId())));
        try {
            awaitMySqlRowLockCompetition();
        } finally {
            allowFirstCommit.countDown();
        }

        first.get(20, TimeUnit.SECONDS);
        second.get(20, TimeUnit.SECONDS);

        User reloaded = userRepository.findById(userId).orElseThrow();
        assertThat(reloaded.getProfileImageAssetId()).isEqualTo(secondAsset.getPublicId());
        List<ImageAsset> assets = imageAssetRepository.findAllByPublicIdIn(
                List.of(firstAsset.getPublicId(), secondAsset.getPublicId()));
        assertThat(assets)
                .extracting(ImageAsset::getPublicId, ImageAsset::getBindingStatus)
                .containsExactlyInAnyOrder(
                        tuple(firstAsset.getPublicId(), ImageBindingStatus.RETIRED),
                        tuple(secondAsset.getPublicId(), ImageBindingStatus.BOUND));
    }

    private UpdateMyInfoRequest imageOnly(UUID assetId) {
        return new UpdateMyInfoRequest(null, null, null, null, assetId);
    }

    private void awaitMySqlRowLockCompetition() throws InterruptedException, SQLException {
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                try (ResultSet resultSet = statement.executeQuery(
                        "SELECT COUNT(*) FROM performance_schema.data_lock_waits")) {
                    resultSet.next();
                    if (resultSet.getInt(1) > 0) {
                        return;
                    }
                }
                Thread.sleep(50);
            }
        }
        throw new AssertionError("MySQL row lock 경쟁이 제한 시간 안에 관찰되지 않았습니다");
    }

    private void await(CountDownLatch latch, String description) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException(description + " 대기 시간이 초과되었습니다");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(description + " 대기가 중단되었습니다", exception);
        }
    }

    /** 회원 본인이 업로드를 마친 READY·UNBOUND PROFILE asset — claim 조건(소유자·READY·UNBOUND)을 만족한다. */
    private ImageAsset profileAsset(Long userId) {
        UUID assetId = UUID.randomUUID();
        ImageAsset asset = ImageAsset.createDirectUpload(
                assetId,
                MediaPurpose.PROFILE,
                MediaOwnerType.USER,
                userId,
                "media/originals/%s/original".formatted(assetId),
                "image/jpeg",
                1024L,
                LocalDateTime.now().plusMinutes(5));
        UUID jobId = UUID.randomUUID();
        asset.beginInitialProcessing(
                "version-1", "\"etag-1\"", 1, SPEC_DIGEST, jobId, LocalDateTime.now());
        asset.completeCurrentProcessing(
                jobId, 1, SPEC_DIGEST, "image/jpeg", 1024L, 3024, 4032, SOURCE_CHECKSUM);
        return asset;
    }
}
