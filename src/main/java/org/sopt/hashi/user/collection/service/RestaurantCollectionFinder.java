package org.sopt.hashi.user.collection.service;

import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.user.code.UserErrorCode;
import org.sopt.hashi.user.collection.domain.RestaurantCollection;
import org.sopt.hashi.user.collection.domain.RestaurantCollectionRepository;
import org.springframework.stereotype.Component;

/**
 * 컬렉션 접근 규칙 — 소유자 전용 조회와 공개 범위에 따른 열람 조회를 한곳에서 판정한다.
 * 없는 컬렉션은 COLLECTION_NOT_FOUND(404), 남의 컬렉션에 대한 편집은 FORBIDDEN(403), 비공개 컬렉션 열람은
 * COLLECTION_PRIVATE(403)으로 구분해 클라이언트가 "삭제됨"과 "비공개 전환됨"을 다르게 안내할 수 있게 한다.
 */
@Component
class RestaurantCollectionFinder {

    private final RestaurantCollectionRepository restaurantCollectionRepository;
    private final CurrentUserProvider currentUserProvider;

    RestaurantCollectionFinder(RestaurantCollectionRepository restaurantCollectionRepository,
                               CurrentUserProvider currentUserProvider) {
        this.restaurantCollectionRepository = restaurantCollectionRepository;
        this.currentUserProvider = currentUserProvider;
    }

    /** 현재 사용자가 소유한 컬렉션 — 편집·삭제·저장·이동처럼 소유자만 할 수 있는 작업용. */
    RestaurantCollection findOwned(Long collectionId) {
        RestaurantCollection collection = restaurantCollectionRepository.findById(collectionId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.COLLECTION_NOT_FOUND));
        if (!collection.isOwnedBy(currentUserProvider.currentUserId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return collection;
    }

    /** 열람 가능한 컬렉션 — 공개 컬렉션은 비로그인 포함 누구나, 비공개 컬렉션은 소유자만. */
    RestaurantCollection findVisible(Long collectionId) {
        RestaurantCollection collection = restaurantCollectionRepository.findById(collectionId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.COLLECTION_NOT_FOUND));
        if (!collection.isPublic() && !isOwner(collection)) {
            throw new BusinessException(UserErrorCode.COLLECTION_PRIVATE);
        }
        return collection;
    }

    /** 비로그인·온보딩·어드민 토큰은 회원이 아니므로 소유자가 아니다. */
    boolean isOwner(RestaurantCollection collection) {
        return currentUserProvider.isAuthenticatedUser()
                && collection.isOwnedBy(currentUserProvider.currentUserId());
    }
}
