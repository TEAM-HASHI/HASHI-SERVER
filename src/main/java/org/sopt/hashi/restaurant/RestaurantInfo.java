package org.sopt.hashi.restaurant;

import org.sopt.hashi.media.ImageReference;

/**
 * 모듈 간 전달용 식당 요약 DTO. 의존 모듈의 목록 표시·enrich에 필요한 최소 필드만 담는다(§2-2 {@code <Context>Info}).
 * {@code address}는 목록에서도 식당 위치를 보여주기 위한 live enrich 값(예약에 스냅샷하지 않음 — 항상 최신).
 * 대표 이미지는 object key가 아닌 전환기 {@link ImageReference}로 전달한다. 대표 이미지가 없으면 null.
 */
public record RestaurantInfo(
        Long id,
        String name,
        String address,
        ImageReference thumbnailImageReference
) {

    /** legacy 테스트와 호출부의 점진 전환용 생성자. */
    public RestaurantInfo(Long id, String name, String address, String imageUrl) {
        this(
                id,
                name,
                address,
                imageUrl == null ? null : ImageReference.legacy(imageUrl));
    }

    /**
     * 신규 asset을 legacy URL로 우회하지 않는 임시 호환 accessor다.
     * 최종 HTTP 응답 Service는 {@link #thumbnailImageReference()}와 MediaPort를 사용해야 한다.
     */
    @Deprecated(forRemoval = true)
    public String imageUrl() {
        return thumbnailImageReference == null || thumbnailImageReference.assetId() != null
                ? null
                : thumbnailImageReference.legacyUrl();
    }
}
