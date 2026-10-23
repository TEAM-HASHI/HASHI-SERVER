package org.sopt.hashi.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.media.MediaAssetPurpose;
import org.sopt.hashi.media.MediaAssetUse;
import org.sopt.hashi.review.domain.Review;
import org.sopt.hashi.review.domain.ReviewImage;
import org.sopt.hashi.review.dto.UpdateReviewRequest;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.test.util.ReflectionTestUtils;

class ReviewImageWritePlanTest {
    @Test
    void 생성은_키와_asset을_혼용하지_않고_중복_누락_초과_asset을_거절한다() {
        UUID asset = UUID.randomUUID();
        assertThatThrownBy(() -> ReviewImageWritePlan.create(List.of(), List.of()))
                .isInstanceOf(BusinessException.class);
        for (List<UUID> ids : List.of(List.of(asset, asset), Arrays.asList(asset, null),
                IntStream.range(0, 11).mapToObj(ignored -> UUID.randomUUID()).toList())) {
            assertThatThrownBy(() -> ReviewImageWritePlan.create(null, ids))
                    .isInstanceOf(BusinessException.class);
        }
        var plan = ReviewImageWritePlan.create(null, List.of(asset));
        assertThat(plan.claims()).containsExactly(new MediaAssetUse(asset, MediaAssetPurpose.REVIEW));
        assertThat(plan.images().getFirst().getFileKey()).isNull();
        assertThat(plan.images().getFirst().getImageAssetId()).isEqualTo(asset);
    }

    @Test
    void 수정은_기존_ID를_보존하며_추가만_READY_claim하고_제거만_retire한다() {
        var retained = assetImage(11L, 0);
        var removed = assetImage(12L, 1);
        UUID added = UUID.randomUUID();
        var plan = ReviewImageWritePlan.update(List.of(retained, removed), request(List.of(
                new UpdateReviewRequest.Image(null, added), new UpdateReviewRequest.Image(11L, null))));
        assertThat(plan.images().get(1)).isSameAs(retained);
        assertThat(plan.claims()).containsExactly(new MediaAssetUse(added, MediaAssetPurpose.REVIEW, true));
        assertThat(plan.retires()).containsExactly(new MediaAssetUse(removed.getImageAssetId(), MediaAssetPurpose.REVIEW));
        Review review = Review.create(1L, 1L, 1L, 5, "기존 리뷰 내용입니다.");
        review.replaceImages(List.of(retained, removed));
        review.stageImageOrderChange();
        assertThat(review.getImages()).allSatisfy(image -> assertThat(image.getDisplayOrder()).isNull());
        review.applyImages(plan.images());
        assertThat(review.getImages()).extracting(ReviewImage::getDisplayOrder).containsExactly(0, 1);
        assertThat(review.getImages().get(1).getId()).isEqualTo(11L);
    }

    @Test
    void 남의_association_중복_ID_혼합_source와_빈_source를_거부한다() {
        var retained = assetImage(11L, 0);
        UUID asset = UUID.randomUUID();
        for (List<UpdateReviewRequest.Image> images : List.of(
                List.of(new UpdateReviewRequest.Image(99L, null)),
                List.of(new UpdateReviewRequest.Image(11L, null), new UpdateReviewRequest.Image(11L, null)),
                List.of(new UpdateReviewRequest.Image(null, asset), new UpdateReviewRequest.Image(null, asset)),
                List.of(new UpdateReviewRequest.Image(11L, asset)),
                List.of(new UpdateReviewRequest.Image(null, null)))) {
            assertThatThrownBy(() -> ReviewImageWritePlan.update(List.of(retained), request(images)))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Test
    void key가_남은_backfill도_ID로만_유지하고_legacy_수정은_거부한다() {
        ReviewImage existing = ReviewImage.create("legacy/kept.jpg", 0);
        ReflectionTestUtils.setField(existing, "id", 1L);
        ReflectionTestUtils.setField(existing, "imageAssetId", UUID.randomUUID());
        for (List<String> keys : List.of(List.of("legacy/kept.jpg"), List.<String>of())) {
            assertThatThrownBy(() -> ReviewImageWritePlan.update(List.of(existing), new UpdateReviewRequest(
                    5, List.of("GOOD_VALUE"), "충분히 긴 리뷰 내용입니다.", keys, null)))
                    .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
                            .isEqualTo(org.sopt.hashi.shared.error.CommonErrorCode.INVALID_INPUT));
        }
        var plan = ReviewImageWritePlan.update(List.of(existing), request(
                List.of(new UpdateReviewRequest.Image(1L, null))));
        assertThat(plan.images()).containsExactly(existing);
        assertThat(plan.claims()).isEmpty();
        assertThat(plan.retires()).isEmpty();
    }

    @Test
    void key없는_asset이_있으면_legacy_전체목록으로_실수로_삭제하지_못한다() {
        var existing = assetImage(11L, 0);
        assertThatThrownBy(() -> ReviewImageWritePlan.update(List.of(existing), new UpdateReviewRequest(
                5, List.of("GOOD_VALUE"), "본문만 수정하려던 요청입니다.", List.of(), null)))
                .isInstanceOf(BusinessException.class);
        var explicitRemoval = ReviewImageWritePlan.update(List.of(existing), request(List.of()));
        assertThat(explicitRemoval.retires()).containsExactly(new MediaAssetUse(existing.getImageAssetId(), MediaAssetPurpose.REVIEW));
    }

    private ReviewImage assetImage(Long id, int order) {
        var image = ReviewImage.createAsset(UUID.randomUUID(), order);
        ReflectionTestUtils.setField(image, "id", id);
        return image;
    }

    private UpdateReviewRequest request(List<UpdateReviewRequest.Image> images) {
        return new UpdateReviewRequest(5, List.of("GOOD_VALUE"), "충분히 긴 리뷰 내용입니다.", null, images);
    }
}
