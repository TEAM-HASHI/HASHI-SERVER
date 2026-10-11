package org.sopt.hashi.user.collection.service;

import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.user.code.UserErrorCode;
import org.sopt.hashi.user.collection.domain.RestaurantCollection;
import org.sopt.hashi.user.collection.domain.RestaurantCollectionRepository;
import org.springframework.stereotype.Component;

/**
 * 컬렉션 접근 규칙 — 소유자 전용 조회와 공개 범위에 따른 열람 조회를 한곳에서 판정한다.
 * 없는 컬렉션, 남의 컬렉션에 대한 편집, 남의 비공개 컬렉션 열람을 모두 COLLECTION_NOT_FOUND(404)로 응답한다.
 * 403을 주면 그 id의 컬렉션이 존재한다는 사실이 드러나므로 존재를 숨긴다(auth.md "소유자 전용 리소스" 규칙).
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

    /** 현재 사용자가 소유한 컬렉션 — 편집·삭제·저장·이동처럼 소유자만 할 수 있는 작업용. 미존재와 타인 소유를 구분하지 않는다. */
    RestaurantCollection findOwned(Long collectionId) {
        Long currentUserId = currentUserProvider.currentUserId();
        return restaurantCollectionRepository.findById(collectionId)
                .filter(collection -> collection.isOwnedBy(currentUserId))
                .orElseThrow(() -> new BusinessException(UserErrorCode.COLLECTION_NOT_FOUND));
    }

    RestaurantCollection findOwnedForUpdate(Long collectionId) {
        Long userId = currentUserProvider.currentUserId();
        return restaurantCollectionRepository.findForUpdate(collectionId)
                .filter(collection -> collection.isOwnedBy(userId))
                .orElseThrow(() -> new BusinessException(UserErrorCode.COLLECTION_NOT_FOUND));
    }

    /** 열람 가능한 컬렉션 — 공개 컬렉션은 비로그인 포함 누구나, 비공개 컬렉션은 소유자만. 볼 수 없으면 없는 것과 같게 응답한다. */
    RestaurantCollection findVisible(Long collectionId) {
        return restaurantCollectionRepository.findById(collectionId)
                .filter(this::canView)
                .orElseThrow(() -> new BusinessException(UserErrorCode.COLLECTION_NOT_FOUND));
    }

    private boolean canView(RestaurantCollection collection) {
        return collection.isPublic() || isOwner(collection);
    }

    /** 비로그인·온보딩·어드민 토큰은 회원이 아니므로 소유자가 아니다. */
    boolean isOwner(RestaurantCollection collection) {
        return currentUserProvider.isAuthenticatedUser()
                && collection.isOwnedBy(currentUserProvider.currentUserId());
    }
}
