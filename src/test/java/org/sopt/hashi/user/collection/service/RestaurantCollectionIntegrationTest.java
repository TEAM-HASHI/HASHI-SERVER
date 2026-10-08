package org.sopt.hashi.user.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.config.JpaAuditingConfig;
import org.sopt.hashi.config.TimeConfig;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.restaurant.RestaurantCardInfo;
import org.sopt.hashi.restaurant.RestaurantMapInfo;
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
import org.sopt.hashi.user.collection.dto.CreateRestaurantCollectionRequest;
import org.sopt.hashi.user.collection.dto.MoveSavedRestaurantsRequest;
import org.sopt.hashi.user.collection.dto.RestaurantCollectionListResponse;
import org.sopt.hashi.user.collection.dto.RestaurantCollectionListResponse.RestaurantCollectionSummaryResponse;
import org.sopt.hashi.user.collection.dto.SavedRestaurantListResponse;
import org.sopt.hashi.user.collection.dto.SavedRestaurantListResponse.SavedRestaurantResponse;
import org.sopt.hashi.user.domain.User;
import org.sopt.hashi.user.domain.UserRepository;
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
        RestaurantCollectionFinder.class, SavedRestaurantEnricher.class, JpaAuditingConfig.class,
        RestaurantSaveSummaryService.class, CollectionMapSnapshotStore.class, CollectionMapQueryService.class,
        TimeConfig.class})
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
    private RestaurantSaveSummaryService summaryService;

    @Autowired
    private CollectionMapSnapshotStore snapshotStore;

    @Autowired
    private CollectionMapQueryService mapService;

    @MockitoBean(name = "japanClock")
    private Clock mapClock;

    @Autowired
    private RestaurantCollectionRepository restaurantCollectionRepository;

    @Autowired
    private SavedRestaurantRepository savedRestaurantRepository;

    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private RestaurantPort restaurantPort;

    @MockitoBean
    private MediaPort mediaPort;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @BeforeEach
    void setUp() {
        loginAs(OWNER_ID);
        given(mapClock.instant()).willReturn(Instant.parse("2026-10-01T00:00:00Z"));
        given(restaurantPort.findActiveCards(anyCollection())).willAnswer(invocation -> {
            Collection<Long> restaurantIds = invocation.getArgument(0);
            return restaurantIds.stream().sorted().map(this::card).toList();
        });
    }

    @AfterEach
    void tearDown() {
        savedRestaurantRepository.deleteAllInBatch();
        restaurantCollectionRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    void 컬렉션_생성은_회원_행을_잠가_활성_회원만_허용하고_탈퇴_회원은_찾을_수_없다고_응답한다() {
        User active = saveUser("활성회원", "01011110001", "active@hashi.test");
        User withdrawn = saveUser("탈퇴할회원", "01011110002", "withdrawn@hashi.test");
        withdrawn.withdraw();
        userRepository.saveAndFlush(withdrawn);
        CreateRestaurantCollectionRequest request =
                new CreateRestaurantCollectionRequest("도쿄 맛집", "red", null, "public");

        loginAs(active.getId());
        assertThat(collectionService.create(request)).isNotNull();

        loginAs(withdrawn.getId());
        assertBusinessError(() -> collectionService.create(request), UserErrorCode.NOT_FOUND);

        assertThat(restaurantCollectionRepository.countByUserId(active.getId())).isEqualTo(1L);
        assertThat(restaurantCollectionRepository.countByUserId(withdrawn.getId())).isZero();
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

    @Test
    void 같은_사용자의_공개와_비공개_저장은_하나로_세고_마지막_제거만_저장수를_줄인다() {
        Long first = saveCollection(OWNER_ID, "공개", CollectionVisibility.PUBLIC, 101L);
        Long second = saveCollection(OWNER_ID, "비공개", CollectionVisibility.PRIVATE, 101L);
        saveCollection(OTHER_USER_ID, "다른 회원", CollectionVisibility.PRIVATE, 101L);

        assertThat(summaryService.getSaveCounts(List.of(101L)).restaurants().getFirst().saveCount()).isEqualTo(2);
        assertThat(summaryService.getMySaves(List.of(101L)).restaurants().getFirst().saved()).isTrue();
        collectionService.removeRestaurants(first, List.of(101L));
        assertThat(summaryService.getSaveCounts(List.of(101L)).restaurants().getFirst().saveCount()).isEqualTo(2);
        collectionService.delete(second);
        assertThat(summaryService.getSaveCounts(List.of(101L)).restaurants().getFirst().saveCount()).isEqualTo(1);
        assertThat(summaryService.getMySaves(List.of(101L)).restaurants().getFirst().saved()).isFalse();
    }

    @Test
    void 삭제_미존재_식당은_요약에서_제외하고_요청_순서를_유지한다() {
        saveCollection(OWNER_ID, "저장", CollectionVisibility.PUBLIC, 101L, 102L);
        given(restaurantPort.findActiveCards(anyCollection())).willReturn(List.of(card(101L), card(103L)));

        assertThat(summaryService.getSaveCounts(List.of(103L, 102L, 101L)).restaurants())
                .extracting(item -> item.restaurantId()).containsExactly(103L, 101L);
        assertThat(summaryService.getMySaves(List.of(103L, 102L, 101L)).restaurants())
                .extracting(item -> item.saved()).containsExactly(false, true);
    }

    @Test
    void 요약은_중복_ID와_빈목록_음수_상한초과를_거부한다() {
        for (List<Long> ids : List.of(List.<Long>of(), List.of(1L, 1L), List.of(0L), List.of(-1L),
                java.util.stream.LongStream.rangeClosed(1, 101).boxed().toList())) {
            assertBusinessError(() -> summaryService.getSaveCounts(ids),
                    org.sopt.hashi.shared.error.CommonErrorCode.INVALID_INPUT);
        }
    }

    @Test
    void 식당_Port_실패를_저장수_0이나_미저장으로_숨기지_않는다() {
        given(restaurantPort.findActiveCards(anyCollection())).willThrow(new IllegalStateException("unavailable"));
        assertThatThrownBy(() -> summaryService.getSaveCounts(List.of(1L))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> summaryService.getMySaves(List.of(1L))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 생성과_수정은_이름과_설명의_공백을_보존한다() {
        var created = collectionService.create(new org.sopt.hashi.user.collection.dto.CreateRestaurantCollectionRequest(
                "  도쿄  ", "red", "   ", "public"));
        assertThat(created.name()).isEqualTo("  도쿄  ");
        assertThat(created.description()).isEqualTo("   ");
        var updated = collectionService.update(created.collectionId(),
                new org.sopt.hashi.user.collection.dto.UpdateRestaurantCollectionRequest("  교토  ", null, "  설명  ", null));
        assertThat(updated.name()).isEqualTo("  교토  ");
        assertThat(updated.description()).isEqualTo("  설명  ");
        assertThat(collectionService.update(created.collectionId(),
                new org.sopt.hashi.user.collection.dto.UpdateRestaurantCollectionRequest(null, null, "   ", null))
                .description()).isEqualTo("   ");
        assertThat(collectionService.update(created.collectionId(),
                new org.sopt.hashi.user.collection.dto.UpdateRestaurantCollectionRequest(null, null, null, null))
                .description()).isEqualTo("   ");
        assertThat(collectionService.update(created.collectionId(),
                new org.sopt.hashi.user.collection.dto.UpdateRestaurantCollectionRequest(null, null, "", null))
                .description()).isNull();
    }

    @Test
    void 공백만인_이름은_거부하고_중복이름은_기존_409를_유지한다() {
        for (String name : List.of(" ", "\u00a0\u3000", "\n\t")) {
            assertBusinessError(() -> collectionService.create(
                    new org.sopt.hashi.user.collection.dto.CreateRestaurantCollectionRequest(name, "red", null, "public")),
                    org.sopt.hashi.shared.error.CommonErrorCode.INVALID_INPUT);
        }
        var first = collectionService.create(new org.sopt.hashi.user.collection.dto.CreateRestaurantCollectionRequest(
                "  도쿄  ", "red", null, "public"));
        var second = collectionService.create(new org.sopt.hashi.user.collection.dto.CreateRestaurantCollectionRequest(
                "교토", "red", null, "public"));
        assertBusinessError(() -> collectionService.create(
                new org.sopt.hashi.user.collection.dto.CreateRestaurantCollectionRequest("  도쿄  ", "red", null, "public")),
                UserErrorCode.DUPLICATE_COLLECTION_NAME);
        assertBusinessError(() -> collectionService.update(second.collectionId(),
                new org.sopt.hashi.user.collection.dto.UpdateRestaurantCollectionRequest(first.name(), null, null, null)),
                UserErrorCode.DUPLICATE_COLLECTION_NAME);
    }

    @Test
    void 지도_snapshot_이후_비공개_변경과_삭제는_404이고_멤버십_변경은_409이다() {
        Long id = saveCollection(OWNER_ID, "지도", CollectionVisibility.PUBLIC, 102L, 101L);
        var snapshot = snapshotStore.read(id, null);
        assertThat(snapshot.restaurantIds()).containsExactly(101L, 102L);
        snapshotStore.validate(snapshot, null);
        collectionService.removeRestaurants(id, List.of(101L));
        assertBusinessError(() -> snapshotStore.validate(snapshot, null),
                org.sopt.hashi.shared.error.CommonErrorCode.CONFLICT);
        var current = snapshotStore.read(id, null);
        collectionService.update(id,
                new org.sopt.hashi.user.collection.dto.UpdateRestaurantCollectionRequest(null, null, null, "private"));
        assertBusinessError(() -> snapshotStore.validate(current, null), UserErrorCode.COLLECTION_NOT_FOUND);
        var owned = snapshotStore.read(id, OWNER_ID);
        collectionService.delete(id);
        assertBusinessError(() -> snapshotStore.validate(owned, OWNER_ID), UserErrorCode.COLLECTION_NOT_FOUND);
    }

    @Test
    void 저장_23개_중_좌표없는_2개는_관계를_보존하고_핀만_빠진다() {
        Long id = saveCollection(OWNER_ID, "전체 지도", CollectionVisibility.PUBLIC,
                java.util.stream.LongStream.rangeClosed(1, 23).boxed().toArray(Long[]::new));
        given(restaurantPort.findActiveMapInfos(anyCollection())).willReturn(
                java.util.stream.LongStream.rangeClosed(1, 23)
                        .mapToObj(restaurantId -> mapInfo(restaurantId, restaurantId <= 21)).toList());
        var response = mapService.getMarkers(id);
        assertThat(response.visibleRestaurantCount()).isEqualTo(23);
        assertThat(response.content().size()).isEqualTo(21);
        assertThat(response.locationUnavailableCount()).isEqualTo(2);
        assertThat(response.content()).extracting(marker -> marker.restaurantId())
                .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, 21).boxed().toList());
        assertThat(savedRestaurantIds(id)).hasSize(23);
    }

    @Test
    void 전체_1000개를_한_Port_호출로_조회하고_상한초과는_503이다() {
        Long id = saveCollection(OWNER_ID, "상한 지도", CollectionVisibility.PUBLIC,
                java.util.stream.LongStream.rangeClosed(1, 1000).boxed().toArray(Long[]::new));
        given(restaurantPort.findActiveMapInfos(anyCollection())).willAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return ids.stream().map(restaurantId -> mapInfo(restaurantId, true)).toList();
        });
        assertThat(mapService.getMarkers(id).content().size()).isEqualTo(1000);
        org.mockito.Mockito.verify(restaurantPort).findActiveMapInfos(
                java.util.stream.LongStream.rangeClosed(1, 1000).boxed().toList());
        Long overflow = saveCollection(OWNER_ID, "과량 지도", CollectionVisibility.PUBLIC,
                java.util.stream.LongStream.rangeClosed(1, 1001).boxed().toArray(Long[]::new));
        assertBusinessError(() -> mapService.getMarkers(overflow), UserErrorCode.COLLECTION_MAP_UNAVAILABLE);
    }

    @Test
    void 조회중_공개범위_변경은_404이고_저장관계_이동은_409이다() {
        Long id = saveCollection(OWNER_ID, "공유 지도", CollectionVisibility.PUBLIC, 101L);
        org.mockito.Mockito.doAnswer(invocation -> {
            collectionService.update(id,
                    new org.sopt.hashi.user.collection.dto.UpdateRestaurantCollectionRequest(null, null, null, "private"));
            given(currentUserProvider.isAuthenticatedUser()).willReturn(false);
            return List.of(mapInfo(101L, true));
        }).when(restaurantPort).findActiveMapInfos(anyCollection());
        // 요청 시작 시 비회원이므로 owner ID가 final 검사에 유입되지 않는다.
        given(currentUserProvider.isAuthenticatedUser()).willReturn(false);
        assertBusinessError(() -> mapService.getMarkers(id), UserErrorCode.COLLECTION_NOT_FOUND);
        loginAs(OWNER_ID);
        Long target = saveCollection(OWNER_ID, "이동 대상", CollectionVisibility.PUBLIC);
        org.mockito.Mockito.doAnswer(invocation -> {
            collectionService.moveRestaurants(id, new MoveSavedRestaurantsRequest(target, List.of(101L)));
            return List.of(mapInfo(101L, true));
        }).when(restaurantPort).findActiveMapInfos(anyCollection());
        assertBusinessError(() -> mapService.getMarkers(id), org.sopt.hashi.shared.error.CommonErrorCode.CONFLICT);
    }

    @Test
    void 조회중_삭제와_Port_오류는_정상_핀_응답이_아니다() {
        Long id = saveCollection(OWNER_ID, "삭제 지도", CollectionVisibility.PUBLIC, 101L);
        given(restaurantPort.findActiveMapInfos(anyCollection())).willAnswer(invocation -> {
            collectionService.delete(id);
            return List.of(mapInfo(101L, true));
        });
        assertBusinessError(() -> mapService.getMarkers(id), UserErrorCode.COLLECTION_NOT_FOUND);
        Long failed = saveCollection(OWNER_ID, "실패 지도", CollectionVisibility.PUBLIC, 101L);
        String privateDetail = "collectionId=" + failed + " SELECT private_sql key=synthetic-secret";
        String privateCause = "private-address 35.654321 139.123456";
        var failure = new IllegalStateException(privateDetail, new IllegalArgumentException(privateCause));
        org.mockito.Mockito.doThrow(failure).when(restaurantPort).findActiveMapInfos(anyCollection());
        Logger logger = (Logger) LoggerFactory.getLogger(CollectionMapQueryService.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logger.setLevel(Level.WARN);
        logs.start();
        logger.addAppender(logs);
        try {
            assertThatThrownBy(() -> mapService.getMarkers(failed))
                    .isInstanceOfSatisfying(BusinessException.class, error -> {
                        assertThat(error.getErrorCode()).isEqualTo(UserErrorCode.COLLECTION_MAP_UNAVAILABLE);
                        assertThat(error.getCause()).isSameAs(failure);
                    });
            assertThat(logs.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).isEqualTo(
                        "Collection map failed. operation=collection-map-port exceptionType=IllegalStateException");
                assertThat(event.getArgumentArray()).containsExactly("IllegalStateException");
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getFormattedMessage()).doesNotContain(privateDetail, privateCause,
                        "collectionId=", "private_sql", "synthetic-secret", "35.654321", "139.123456");
            });
        } finally {
            logger.detachAppender(logs);
            logs.stop();
            logger.setLevel(previousLevel);
        }
    }

    @Test
    void Port_조회중_만료된_좌표는_응답직전_다시_제외한다() {
        Long id = saveCollection(OWNER_ID, "만료 지도", CollectionVisibility.PUBLIC, 101L);
        given(restaurantPort.findActiveMapInfos(anyCollection())).willAnswer(invocation -> {
            given(mapClock.instant()).willReturn(Instant.parse("2026-10-02T00:00:00Z"));
            return List.of(mapInfo(101L, true));
        });
        assertThat(mapService.getMarkers(id).content().size()).isZero();
    }

    private RestaurantMapInfo mapInfo(Long id, boolean located) {
        return new RestaurantMapInfo(id, "식당 " + id, "restaurant", "japanese", located
                ? new RestaurantMapInfo.LocationInfo(new BigDecimal("35.6"), new BigDecimal("139.7"),
                        Instant.parse("2026-10-02T00:00:00Z")) : null);
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

    private User saveUser(String nickname, String phone, String email) {
        return userRepository.saveAndFlush(
                User.onboard(nickname, "HASHI", LocalDate.of(1998, 1, 1), phone, email, null));
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
