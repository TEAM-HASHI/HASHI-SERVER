package org.sopt.hashi.user.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.config.JpaAuditingConfig;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.restaurant.RestaurantCardInfo;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.ErrorCode;
import org.sopt.hashi.user.code.UserErrorCode;
import org.sopt.hashi.user.collection.domain.CollectionColor;
import org.sopt.hashi.user.collection.domain.CollectionVisibility;
import org.sopt.hashi.user.collection.domain.RestaurantCollection;
import org.sopt.hashi.user.collection.domain.RestaurantCollectionRepository;
import org.sopt.hashi.user.collection.domain.SavedRestaurant;
import org.sopt.hashi.user.collection.domain.SavedRestaurantRepository;
import org.sopt.hashi.user.collection.dto.MoveSavedRestaurantsRequest;
import org.sopt.hashi.user.collection.dto.RestaurantCollectionListResponse;
import org.sopt.hashi.user.collection.dto.RestaurantCollectionListResponse.RestaurantCollectionSummaryResponse;
import org.sopt.hashi.user.collection.dto.SavedRestaurantListResponse;
import org.sopt.hashi.user.collection.dto.SavedRestaurantListResponse.SavedRestaurantResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 컬렉션 서비스와 실제 저장소·트랜잭션을 함께 검증한다(testing.md §4 트랜잭션 경계). 타 모듈은 포트로 모킹한다.
 * 테스트 자체는 트랜잭션 밖에서 돌려 서비스 트랜잭션의 커밋·롤백 결과를 DB에서 다시 읽는다.
 */
@DataJpaTest
@Import({RestaurantCollectionService.class, SavedRestaurantQueryService.class,
        RestaurantCollectionFinder.class, SavedRestaurantEnricher.class, JpaAuditingConfig.class})
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:restaurant-collection-integration-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RestaurantCollectionIntegrationTest {

    private static final Long OWNER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;

    @Autowired
    private RestaurantCollectionService collectionService;

    @Autowired
    private SavedRestaurantQueryService queryService;

    @Autowired
    private RestaurantCollectionRepository restaurantCollectionRepository;

    @Autowired
    private SavedRestaurantRepository savedRestaurantRepository;

    @MockitoBean
    private RestaurantPort restaurantPort;

    @MockitoBean
    private MediaPort mediaPort;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @BeforeEach
    void setUp() {
        loginAs(OWNER_ID);
        given(restaurantPort.findActiveCards(anyCollection())).willAnswer(invocation -> {
            Collection<Long> restaurantIds = invocation.getArgument(0);
            return restaurantIds.stream().sorted().map(this::card).toList();
        });
    }

    @AfterEach
    void tearDown() {
        savedRestaurantRepository.deleteAllInBatch();
        restaurantCollectionRepository.deleteAllInBatch();
    }

    @Test
    void 이동할_식당이_대상_컬렉션에_이미_있으면_두_컬렉션_모두_DB에_그대로_남는다() {
        Long sourceId = saveCollection(OWNER_ID, "원래 컬렉션", CollectionVisibility.PUBLIC, 101L, 102L);
        Long targetId = saveCollection(OWNER_ID, "대상 컬렉션", CollectionVisibility.PUBLIC, 102L);

        assertBusinessError(
                () -> collectionService.moveRestaurants(
                        sourceId, new MoveSavedRestaurantsRequest(targetId, List.of(101L, 102L))),
                UserErrorCode.RESTAURANT_ALREADY_SAVED);

        assertThat(savedRestaurantIds(sourceId)).containsExactly(101L, 102L);
        assertThat(savedRestaurantIds(targetId)).containsExactly(102L);
    }

    @Test
    void 이동을_반영한_뒤_응답_구성에서_예외가_나면_이동_전체가_롤백된다() {
        Long sourceId = saveCollection(OWNER_ID, "원래 컬렉션", CollectionVisibility.PUBLIC, 101L, 102L);
        Long targetId = saveCollection(OWNER_ID, "대상 컬렉션", CollectionVisibility.PUBLIC);
        given(restaurantPort.findActiveCards(anyCollection()))
                .willThrow(new IllegalStateException("restaurant module unavailable"));

        assertThatThrownBy(() -> collectionService.moveRestaurants(
                sourceId, new MoveSavedRestaurantsRequest(targetId, List.of(101L))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(savedRestaurantIds(sourceId)).containsExactly(101L, 102L);
        assertThat(savedRestaurantIds(targetId)).isEmpty();
    }

    @Test
    void 타인의_비공개_컬렉션은_상세도_저장_식당_목록도_찾을_수_없다고_응답한다() {
        Long privateId = saveCollection(OWNER_ID, "비공개 컬렉션", CollectionVisibility.PRIVATE, 101L);
        loginAs(OTHER_USER_ID);

        assertBusinessError(() -> collectionService.getCollection(privateId), UserErrorCode.COLLECTION_NOT_FOUND);
        assertBusinessError(
                () -> queryService.getSavedRestaurants(privateId, null, null, null, null),
                UserErrorCode.COLLECTION_NOT_FOUND);
    }

    @Test
    void 내_컬렉션_목록은_최신순으로_커서를_따라_이어_조회하고_다른_사용자의_컬렉션은_섞이지_않는다() {
        Long oldest = saveCollection(OWNER_ID, "첫째", CollectionVisibility.PUBLIC);
        Long middle = saveCollection(OWNER_ID, "둘째", CollectionVisibility.PUBLIC);
        Long newest = saveCollection(OWNER_ID, "셋째", CollectionVisibility.PUBLIC);
        saveCollection(OTHER_USER_ID, "남의 컬렉션", CollectionVisibility.PUBLIC);

        RestaurantCollectionListResponse firstPage = collectionService.getMyCollections(null, null, 2);
        RestaurantCollectionListResponse secondPage = collectionService.getMyCollections(
                null, firstPage.nextCursor(), 2);

        assertThat(firstPage.collections())
                .extracting(RestaurantCollectionSummaryResponse::collectionId)
                .containsExactly(newest, middle);
        assertThat(firstPage.hasNext()).isTrue();
        assertThat(firstPage.nextCursor()).isEqualTo(middle);
        assertThat(firstPage.totalCount()).isEqualTo(3L);
        assertThat(secondPage.collections())
                .extracting(RestaurantCollectionSummaryResponse::collectionId)
                .containsExactly(oldest);
        assertThat(secondPage.hasNext()).isFalse();
        assertThat(secondPage.nextCursor()).isNull();
        assertThat(secondPage.totalCount()).isEqualTo(3L);
    }

    @Test
    void 저장_식당_목록은_커서로_다음_페이지를_이어_조회한다() {
        Long collectionId = saveCollection(OWNER_ID, "도쿄 맛집", CollectionVisibility.PUBLIC, 101L, 102L, 103L);

        SavedRestaurantListResponse firstPage = queryService.getSavedRestaurants(
                collectionId, "latest", null, null, 2);
        SavedRestaurantListResponse secondPage = queryService.getSavedRestaurants(
                collectionId, "latest", null, firstPage.nextCursor(), 2);

        assertThat(firstPage.content())
                .extracting(SavedRestaurantResponse::restaurantId)
                .containsExactly(103L, 102L);
        assertThat(firstPage.hasNext()).isTrue();
        assertThat(secondPage.content())
                .extracting(SavedRestaurantResponse::restaurantId)
                .containsExactly(101L);
        assertThat(secondPage.hasNext()).isFalse();
        assertThat(secondPage.nextCursor()).isNull();
    }

    private Long saveCollection(Long userId, String name, CollectionVisibility visibility, Long... restaurantIds) {
        RestaurantCollection collection = RestaurantCollection.create(
                userId, name, CollectionColor.RED, null, visibility);
        for (Long restaurantId : restaurantIds) {
            collection.save(restaurantId);
        }
        return restaurantCollectionRepository.saveAndFlush(collection).getId();
    }

    private List<Long> savedRestaurantIds(Long collectionId) {
        return savedRestaurantRepository.findAllByCollection_IdInOrderByIdAsc(List.of(collectionId)).stream()
                .map(SavedRestaurant::getRestaurantId)
                .toList();
    }

    private void loginAs(Long userId) {
        given(currentUserProvider.isAuthenticatedUser()).willReturn(true);
        given(currentUserProvider.currentUserId()).willReturn(userId);
    }

    private RestaurantCardInfo card(Long restaurantId) {
        return new RestaurantCardInfo(restaurantId, "식당 " + restaurantId, "도쿄", "sushi", "restaurant",
                new BigDecimal("4.5"), 10L, null);
    }

    private void assertBusinessError(ThrowingCallable callable, ErrorCode errorCode) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
