package org.sopt.hashi.review.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.media.MediaImage;
import org.sopt.hashi.media.MediaImageRole;
import org.sopt.hashi.media.MediaImageStatus;
import org.sopt.hashi.review.dto.RestaurantReviewResponse.ReviewSummaryResponse;
import org.sopt.hashi.review.dto.ReviewEditContextResponse.ReviewEditImageResponse;

class ReviewMediaJsonContractTest {
    private static final UUID ASSET_ID = UUID.fromString("6a72ef3b-1491-4678-b72f-3537ae107573");
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 1, 1, 12, 0);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void 작성_요청은_기존_key와_새_asset_필드를_각각_기본_생성자로_읽는다() throws Exception {
        CreateReviewRequest legacy = mapper.readValue("""
                {"reservationId":1,"rating":5,"keywordCodes":["GOOD_VALUE"],
                 "content":"충분히 긴 리뷰 내용입니다.","imageFileKeys":["uploads/reviews/old.jpg"]}
                """, CreateReviewRequest.class);
        assertThat(legacy.imageFileKeys()).containsExactly("uploads/reviews/old.jpg");
        assertThat(legacy.imageAssetIds()).isNull();

        CreateReviewRequest asset = mapper.readValue("""
                {"reservationId":1,"rating":5,"keywordCodes":["GOOD_VALUE"],
                 "content":"충분히 긴 리뷰 내용입니다.","imageAssetIds":["%s"]}
                """.formatted(ASSET_ID), CreateReviewRequest.class);
        assertThat(asset.imageFileKeys()).isNull();
        assertThat(asset.imageAssetIds()).containsExactly(ASSET_ID);
    }

    @Test
    void 수정_요청은_기존_key와_사진_ID_유지_신규_asset_추가를_각각_읽는다() throws Exception {
        UpdateReviewRequest legacy = mapper.readValue("""
                {"rating":5,"keywordCodes":["GOOD_VALUE"],"content":"충분히 긴 리뷰 내용입니다.",
                 "imageFileKeys":["uploads/reviews/old.jpg"]}
                """, UpdateReviewRequest.class);
        assertThat(legacy.imageFileKeys()).containsExactly("uploads/reviews/old.jpg");
        assertThat(legacy.images()).isNull();

        UpdateReviewRequest asset = mapper.readValue("""
                {"rating":5,"keywordCodes":["GOOD_VALUE"],"content":"충분히 긴 리뷰 내용입니다.",
                 "images":[{"reviewImageId":501},{"imageAssetId":"%s"}]}
                """.formatted(ASSET_ID), UpdateReviewRequest.class);
        assertThat(asset.imageFileKeys()).isNull();
        assertThat(asset.images()).containsExactly(
                new UpdateReviewRequest.Image(501L, null), new UpdateReviewRequest.Image(null, ASSET_ID));
    }

    @Test
    void 리뷰_목록은_빈_배열_대신_실제_previewImages의_ID_순서_상태를_직렬화한다() {
        var response = new ReviewSummaryResponse(10L, "하루", null, null, 5, "리뷰 내용입니다.",
                List.of("친절해요"), List.of("https://cdn.test/legacy.jpg"),
                slots(MediaImageRole.REVIEW_PREVIEW), 2, CREATED_AT);

        JsonNode json = mapper.valueToTree(response);

        assertThat(json.get("imageCount").asInt()).isEqualTo(2);
        assertThat(json.get("previewImageUrls")).hasSize(1);
        assertSlots(json.get("previewImages"), MediaImageRole.REVIEW_PREVIEW);
    }

    @Test
    void 내_리뷰_상세는_기존_URL_목록과_새_images를_함께_직렬화한다() {
        var response = new MyReviewDetailResponse(10L, 1L, "식당", null, null, CREATED_AT,
                2, 0, "하루", 5, "리뷰 내용입니다.", List.of("친절해요"),
                List.of("https://cdn.test/legacy.jpg"), slots(MediaImageRole.REVIEW_DETAIL), CREATED_AT);

        JsonNode json = mapper.valueToTree(response);

        assertThat(json.get("imageUrls")).hasSize(1);
        assertSlots(json.get("images"), MediaImageRole.REVIEW_DETAIL);
    }

    @Test
    void 수정_사진은_legacy_key를_유지하고_asset은_ID와_상태를_반환한다() {
        var legacy = new ReviewEditImageResponse("uploads/reviews/old.jpg", "https://cdn.test/legacy.jpg",
                0, 501L, null, null);
        var asset = new ReviewEditImageResponse(null, null, 1, 502L, ASSET_ID,
                processing(MediaImageRole.REVIEW_DETAIL));

        JsonNode legacyJson = mapper.valueToTree(legacy);
        JsonNode assetJson = mapper.valueToTree(asset);

        assertThat(legacyJson.get("fileKey").asText()).isEqualTo("uploads/reviews/old.jpg");
        assertThat(legacyJson.get("reviewImageId").asLong()).isEqualTo(501L);
        assertThat(assetJson.has("fileKey")).isFalse();
        assertThat(assetJson.has("imageUrl")).isFalse();
        assertThat(assetJson.get("reviewImageId").asLong()).isEqualTo(502L);
        assertThat(assetJson.get("displayOrder").asInt()).isEqualTo(1);
        assertThat(assetJson.get("imageAssetId").asText()).isEqualTo(ASSET_ID.toString());
        assertThat(assetJson.at("/image/status").asText()).isEqualTo("PROCESSING");
    }

    private List<ReviewImageResponse> slots(MediaImageRole role) {
        return List.of(new ReviewImageResponse(501L, 0, null, "https://cdn.test/legacy.jpg"),
                new ReviewImageResponse(502L, 1, processing(role), null));
    }

    private MediaImage processing(MediaImageRole role) {
        return new MediaImage(ASSET_ID, role, MediaImageStatus.PROCESSING, null, List.of());
    }

    private void assertSlots(JsonNode images, MediaImageRole role) {
        assertThat(images).hasSize(2);
        assertThat(images.get(0).get("reviewImageId").asLong()).isEqualTo(501L);
        assertThat(images.get(0).get("displayOrder").asInt()).isZero();
        assertThat(images.get(0).get("legacyUrl").asText()).isEqualTo("https://cdn.test/legacy.jpg");
        assertThat(images.get(1).get("reviewImageId").asLong()).isEqualTo(502L);
        assertThat(images.get(1).get("displayOrder").asInt()).isEqualTo(1);
        assertThat(images.get(1).get("legacyUrl").isNull()).isTrue();
        assertThat(images.get(1).at("/image/assetId").asText()).isEqualTo(ASSET_ID.toString());
        assertThat(images.get(1).at("/image/role").asText()).isEqualTo(role.name());
        assertThat(images.get(1).at("/image/status").asText()).isEqualTo("PROCESSING");
    }
}
