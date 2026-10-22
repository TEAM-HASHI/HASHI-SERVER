package org.sopt.hashi.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.auth.ActorType;
import org.sopt.hashi.auth.CurrentActor;
import org.sopt.hashi.auth.CurrentActorProvider;
import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.config.JpaAuditingConfig;
import org.sopt.hashi.media.domain.ImageAsset;
import org.sopt.hashi.media.domain.ImageAssetRepository;
import org.sopt.hashi.media.domain.ImageFormat;
import org.sopt.hashi.media.domain.ImageRole;
import org.sopt.hashi.media.domain.MediaOwnerType;
import org.sopt.hashi.media.domain.MediaPurpose;
import org.sopt.hashi.media.internal.spec.MediaSpecRegistry;
import org.sopt.hashi.point.PointPort;
import org.sopt.hashi.reservation.ReservationPort;
import org.sopt.hashi.reservation.ReservationReviewInfo;
import org.sopt.hashi.reservation.ReservationStatus;
import org.sopt.hashi.reservation.ReservationType;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.review.domain.Review;
import org.sopt.hashi.review.domain.ReviewImage;
import org.sopt.hashi.review.domain.ReviewRepository;
import org.sopt.hashi.review.dto.CreateReviewRequest;
import org.sopt.hashi.review.dto.UpdateReviewRequest;
import org.sopt.hashi.review.service.MyReviewService;
import org.sopt.hashi.review.service.ReviewWriteService;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.storage.FileStorage;
import org.sopt.hashi.user.UserPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 실제 MySQL/Flyway와 media port를 사용해 콘텐츠 저장과 binding의 원자성을 확인한다. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({MyReviewService.class, ReviewWriteService.class, MediaPortImpl.class,
        MediaPurposeAccessPolicy.class, MediaSpecRegistry.class, ObjectMapper.class, JpaAuditingConfig.class})
@TestPropertySource(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class ReviewMediaIntegrationTest {
    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    @Autowired MyReviewService myReviews;
    @Autowired ReviewWriteService writeReviews;
    @Autowired ReviewRepository reviews;
    @Autowired ImageAssetRepository assets;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired MediaSpecRegistry specs;
    @MockitoBean CurrentActorProvider actors;
    @MockitoBean CurrentUserProvider users;
    @MockitoBean ReservationPort reservations;
    @MockitoBean RestaurantPort restaurants;
    @MockitoBean PointPort points;
    @MockitoBean UserPort userPort;
    @MockitoBean FileStorage storage;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from review_keyword");
        jdbc.update("delete from review_image");
        jdbc.update("delete from review");
        jdbc.update("delete from image_rendition");
        jdbc.update("delete from image_asset");
        when(actors.currentActor()).thenReturn(new CurrentActor(ActorType.USER, 7L));
        when(users.currentUserId()).thenReturn(7L);
        when(userPort.lockActiveUser(7L)).thenReturn(true);
        when(restaurants.existsById(10L)).thenReturn(true);
        when(reservations.getReviewInfoByIdAndUserId(100L, 7L)).thenReturn(
                new ReservationReviewInfo(100L, 7L, ReservationType.STANDARD, 10L, null, null,
                        LocalDateTime.now(), 2, 0, 0, ReservationStatus.VISITED));
    }

    @Test
    void 생성은_PROCESSING과_READY를_claim하고_원본_key를_저장하지_않는다() {
        var processing = asset(false, 7L, MediaPurpose.REVIEW, false);
        var ready = asset(true, 7L, MediaPurpose.REVIEW, false);
        long reviewId = writeReviews.create(create(List.of(processing.getPublicId(), ready.getPublicId()))).reviewId();
        assertThat(binding(processing)).isEqualTo("BOUND");
        assertThat(binding(ready)).isEqualTo("BOUND");
        assertThat(jdbc.queryForObject("select count(*) from review_image where review_id=? and file_key is null",
                Integer.class, reviewId)).isEqualTo(2);
        assertThat(jdbc.queryForList("select display_order from review_image order by display_order", Integer.class))
                .containsExactly(0, 1);
    }

    @Test
    void 포인트_실패시_리뷰저장과_모든_claim을_함께_롤백한다() {
        var asset = asset(true, 7L, MediaPurpose.REVIEW, false);
        when(points.earnReviewReward(7L, 100L)).thenThrow(new IllegalStateException("synthetic reward failure"));
        assertThatThrownBy(() -> writeReviews.create(create(List.of(asset.getPublicId()))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(reviews.count()).isZero();
        assertThat(binding(asset)).isEqualTo("UNBOUND");
    }

    @Test
    void 타인_다른목적_이미연결_asset은_리뷰생성을_거부한다() {
        for (var invalid : List.of(asset(true, 8L, MediaPurpose.REVIEW, false),
                asset(true, 7L, MediaPurpose.PROFILE, false), asset(true, 7L, MediaPurpose.REVIEW, true))) {
            assertThatThrownBy(() -> writeReviews.create(create(List.of(invalid.getPublicId()))))
                    .isInstanceOf(BusinessException.class);
        }
        assertThat(reviews.count()).isZero();
    }

    @Test
    void 수정은_DB순서제약에서_유지ID를_보존하며_추가claim과_제거retire를_커밋한다() {
        var first = asset(true, 7L, MediaPurpose.REVIEW, true);
        var second = asset(true, 7L, MediaPurpose.REVIEW, true);
        var removed = asset(true, 7L, MediaPurpose.REVIEW, true);
        Review review = review(List.of(first, second, removed));
        var originalIds = imageIds(review.getId());
        var added = asset(true, 7L, MediaPurpose.REVIEW, false);
        myReviews.updateMyReview(review.getId(), update(5, List.of(
                new UpdateReviewRequest.Image(originalIds.get(1), null),
                new UpdateReviewRequest.Image(null, added.getPublicId()),
                new UpdateReviewRequest.Image(originalIds.getFirst(), null))));
        assertThat(imageIds(review.getId()).getFirst()).isEqualTo(originalIds.get(1));
        assertThat(imageIds(review.getId()).get(2)).isEqualTo(originalIds.getFirst());
        assertThat(binding(first)).isEqualTo("BOUND");
        assertThat(binding(second)).isEqualTo("BOUND");
        assertThat(binding(removed)).isEqualTo("RETIRED");
        assertThat(binding(added)).isEqualTo("BOUND");
        assertFinalOrders();
        assertThatThrownBy(() -> jdbc.update("update review_image set display_order=10 where id=?", originalIds.getFirst()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("update review_image set display_order=0 where id=?", originalIds.getFirst()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void 수정통계_실패시_임시NULL순서와_claim_retire를_모두_롤백한다() {
        var retained = asset(true, 7L, MediaPurpose.REVIEW, true);
        var removed = asset(true, 7L, MediaPurpose.REVIEW, true);
        var added = asset(true, 7L, MediaPurpose.REVIEW, false);
        Review review = review(List.of(retained, removed));
        var originalIds = imageIds(review.getId());
        doThrow(new IllegalStateException("synthetic statistics failure"))
                .when(restaurants).updateReviewRatingStatistics(10L, 5, 3);
        assertThatThrownBy(() -> myReviews.updateMyReview(review.getId(), update(3, List.of(
                new UpdateReviewRequest.Image(null, added.getPublicId()),
                new UpdateReviewRequest.Image(originalIds.getFirst(), null)))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(imageIds(review.getId())).isEqualTo(originalIds);
        assertThat(binding(removed)).isEqualTo("BOUND");
        assertThat(binding(added)).isEqualTo("UNBOUND");
        assertFinalOrders();
    }

    @Test
    void 처리중_교체는_거부하고_콘텐츠_softdelete는_BOUND와_association을_보존한다() {
        var old = asset(true, 7L, MediaPurpose.REVIEW, true);
        var added = asset(false, 7L, MediaPurpose.REVIEW, false);
        Review review = review(List.of(old));
        var originalIds = imageIds(review.getId());
        assertThatThrownBy(() -> myReviews.updateMyReview(review.getId(), update(5,
                List.of(new UpdateReviewRequest.Image(null, added.getPublicId())))))
                .isInstanceOf(BusinessException.class);
        assertThat(imageIds(review.getId())).isEqualTo(originalIds);
        myReviews.deleteMyReview(review.getId());
        assertThat(binding(old)).isEqualTo("BOUND");
        assertThat(imageIds(review.getId())).isEqualTo(originalIds);
        assertThat(jdbc.queryForObject("select deleted from review where id=?", Boolean.class, review.getId())).isTrue();
    }

    private void assertFinalOrders() {
        assertThat(jdbc.queryForObject("select count(*) from review_image where display_order is null", Integer.class))
                .isZero();
        transactions.executeWithoutResult(status -> reviews.findAll().forEach(review -> {
            for (int index = 0; index < review.getImages().size(); index++) {
                assertThat(review.getImages().get(index).getDisplayOrder()).isEqualTo(index);
            }
        }));
    }

    private List<Long> imageIds(long reviewId) {
        return jdbc.queryForList("select id from review_image where review_id=? order by display_order", Long.class, reviewId);
    }

    private String binding(ImageAsset asset) {
        return jdbc.queryForObject("select binding_status from image_asset where public_id=?", String.class,
                asset.getPublicId().toString());
    }

    private Review review(List<ImageAsset> media) {
        Review review = Review.create(100L, 10L, 7L, 5, "충분히 긴 리뷰 내용입니다.");
        review.replaceKeywords(List.of("GOOD_VALUE"));
        review.replaceImages(java.util.stream.IntStream.range(0, media.size())
                .mapToObj(index -> ReviewImage.createAsset(media.get(index).getPublicId(), index)).toList());
        return reviews.saveAndFlush(review);
    }

    private CreateReviewRequest create(List<UUID> ids) {
        return new CreateReviewRequest(100L, 5, List.of("GOOD_VALUE"), "충분히 긴 리뷰 내용입니다.", null, ids);
    }

    private UpdateReviewRequest update(int rating, List<UpdateReviewRequest.Image> images) {
        return new UpdateReviewRequest(rating, List.of("GOOD_VALUE"), "수정 후에도 충분히 긴 내용입니다.", null, images);
    }

    private ImageAsset asset(boolean ready, long owner, MediaPurpose purpose, boolean bound) {
        UUID id = UUID.randomUUID();
        var asset = ImageAsset.createDirectUpload(id, purpose, MediaOwnerType.USER, owner,
                "media/originals/" + id + "/original", "image/jpeg", 1024, LocalDateTime.now().plusMinutes(5));
        UUID job = UUID.randomUUID();
        var spec = specs.findDefinition(1).orElseThrow();
        asset.beginInitialProcessing("synthetic-version", "synthetic-etag", 1, spec.digest(), job, LocalDateTime.now());
        if (ready) {
            asset.addRendition(job, 1, spec.digest(), ImageRole.REVIEW_PREVIEW, ImageFormat.WEBP,
                    270, 270, 100, "media/renditions/" + id + "/review-preview/270.webp");
            asset.completeCurrentProcessing(job, 1, spec.digest(), "image/jpeg", 1024, 1000, 1000,
                    "47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=");
        }
        if (bound) {
            asset.bind();
        }
        return assets.saveAndFlush(asset);
    }
}
