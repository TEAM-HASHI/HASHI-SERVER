package org.sopt.hashi.restaurant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.LongStream;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.hashi.media.MediaAssetPurpose;
import org.sopt.hashi.media.MediaAssetUse;
import org.sopt.hashi.media.MediaImage;
import org.sopt.hashi.media.MediaImageRequest;
import org.sopt.hashi.media.MediaImageRole;
import org.sopt.hashi.media.MediaImageStatus;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.media.code.MediaErrorCode;
import org.sopt.hashi.restaurant.AdminRestaurantCommand;
import org.sopt.hashi.restaurant.AdminRestaurantCommand.ImageCommand;
import org.sopt.hashi.restaurant.AdminRestaurantCommand.MenuCommand;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.restaurant.domain.PriceCurrency;
import org.sopt.hashi.restaurant.domain.Restaurant;
import org.sopt.hashi.restaurant.domain.RestaurantBusinessHour;
import org.sopt.hashi.restaurant.domain.RestaurantCursor;
import org.sopt.hashi.restaurant.domain.RestaurantGenre;
import org.sopt.hashi.restaurant.domain.RestaurantImage;
import org.sopt.hashi.restaurant.domain.RestaurantMenu;
import org.sopt.hashi.restaurant.domain.RestaurantRepository;
import org.sopt.hashi.restaurant.domain.RestaurantSort;
import org.sopt.hashi.restaurant.dto.RestaurantListResponse;
import org.sopt.hashi.restaurant.dto.RestaurantMenuDetailResponse;
import org.sopt.hashi.restaurant.dto.RestaurantMenuListResponse;
import org.sopt.hashi.restaurant.dto.RestaurantSearchKeywordRecommendationResponse;
import org.sopt.hashi.restaurant.dto.RestaurantSearchSuggestionResponse;
import org.sopt.hashi.restaurant.dto.RestaurantStoreInformationResponse;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.storage.FileStorage;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RestaurantServiceTest {

    private static final Clock JAPAN_CLOCK = Clock.fixed(
            Instant.parse("2026-07-12T15:30:00Z"),
            ZoneId.of("Asia/Tokyo")
    );

    @Mock
    private RestaurantRepository restaurantRepository;

    @Mock
    private FileStorage fileStorage;

    @Mock
    private MediaPort mediaPort;

    private RestaurantService createRestaurantService() {
        return new RestaurantService(restaurantRepository, fileStorage, mediaPort, JAPAN_CLOCK);
    }

    @Test
    void 어드민_식당_등록에서_이미지나_해시태그가_비어있으면_거부한다() {
        RestaurantService restaurantService = createRestaurantService();
        AdminRestaurantCommand emptyHashtagCommand = createAdminCommand(
                List.of("restaurants/1/thumbnail.jpg"),
                List.of()
        );
        AdminRestaurantCommand emptyImageCommand = createAdminCommand(
                List.of(),
                List.of("스시")
        );

        assertThatThrownBy(() -> restaurantService.createByAdmin(emptyHashtagCommand))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));
        assertThatThrownBy(() -> restaurantService.createByAdmin(emptyImageCommand))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));
        verifyNoInteractions(restaurantRepository);
    }

    @Test
    void 어드민_식당_등록에서_현지_식당명이_없으면_거부한다() {
        RestaurantService restaurantService = createRestaurantService();
        AdminRestaurantCommand command = new AdminRestaurantCommand(
                "히마와리 스시",
                null,
                "식당 소개",
                "매장 상세 설명",
                "도쿄도 신주쿠구",
                "도쿄",
                "sushi",
                "sushi",
                "JPY",
                BigDecimal.valueOf(1000),
                BigDecimal.valueOf(3000),
                List.of("restaurants/1/thumbnail.jpg"),
                List.of(),
                List.of("스시"),
                List.of(),
                null
        );

        assertThatThrownBy(() -> restaurantService.createByAdmin(command))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));
        verifyNoInteractions(restaurantRepository);
    }

    @Test
    void 어드민_식당_수정에서_해시태그를_빈_목록으로_교체할_수_없다() {
        RestaurantService restaurantService = createRestaurantService();
        AdminRestaurantCommand command = new AdminRestaurantCommand(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null
        );

        assertThatThrownBy(() -> restaurantService.updateByAdmin(1L, command))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));
        verifyNoInteractions(restaurantRepository);
    }

    @Test
    void 어드민_식당_수정에서_이미지를_빈_목록으로_교체할_수_없다() {
        RestaurantService restaurantService = createRestaurantService();
        AdminRestaurantCommand command = new AdminRestaurantCommand(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null
        );

        assertThatThrownBy(() -> restaurantService.updateByAdmin(1L, command))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));
        verifyNoInteractions(restaurantRepository);
    }

    @Test
    void 어드민_식당_수정에서_현지_식당명을_공백으로_바꿀_수_없다() {
        RestaurantService restaurantService = createRestaurantService();
        AdminRestaurantCommand command = new AdminRestaurantCommand(
                null,
                " ",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        assertThatThrownBy(() -> restaurantService.updateByAdmin(1L, command))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));
        verifyNoInteractions(restaurantRepository);
    }

    @Test
    void 어드민_식당_등록과_수정은_각_동작에서_지원하지_않는_이미지_필드를_거부한다() {
        RestaurantService restaurantService = createRestaurantService();
        UUID assetId = UUID.randomUUID();
        AdminRestaurantCommand createCommand = new AdminRestaurantCommand(
                "히마와리 스시", "Himawari Sushi", "식당 소개", "매장 상세 설명",
                "도쿄도 신주쿠구", "도쿄", "sushi", "sushi", "JPY",
                BigDecimal.valueOf(1_000), BigDecimal.valueOf(3_000),
                null, List.of(assetId), List.of(new ImageCommand(null, assetId)),
                null, List.of("스시"), null, null
        );
        AdminRestaurantCommand updateCommand = new AdminRestaurantCommand(
                null, null, null, null, null, null, null, null, null, null, null,
                null, List.of(assetId), null, null, null, null, null
        );

        assertThatThrownBy(() -> restaurantService.createByAdmin(createCommand))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));
        assertThatThrownBy(() -> restaurantService.updateByAdmin(1L, updateCommand))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));
        verifyNoInteractions(restaurantRepository, mediaPort);
    }

    @Test
    void 어드민_식당_삭제는_수정과_같은_write_lock으로_Aggregate를_조회한다() {
        RestaurantService restaurantService = createRestaurantService();
        Restaurant restaurant = createRestaurant(1L, 4.8, 100L);
        given(restaurantRepository.findByIdForUpdate(1L)).willReturn(Optional.of(restaurant));

        restaurantService.deleteByAdmin(1L);

        assertThat(restaurant.isDeleted()).isTrue();
        verify(restaurantRepository).findByIdForUpdate(1L);
        verify(restaurantRepository, never()).findById(1L);
    }

    @Test
    void 어드민_메뉴_수정은_기존_ID를_유지하고_신규와_삭제를_동기화한다() {
        RestaurantService restaurantService = createRestaurantService();
        Restaurant restaurant = createRestaurant(1L, 4.8, 100L);
        RestaurantMenu retainedMenu = createMenu(10L, "기존 메뉴", true);
        RestaurantMenu removedMenu = createMenu(20L, "삭제 메뉴", false);
        restaurant.addMenu(retainedMenu);
        restaurant.addMenu(removedMenu);
        given(restaurantRepository.findByIdForUpdate(1L)).willReturn(Optional.of(restaurant));
        AdminRestaurantCommand command = updateMenuCommand(List.of(
                new MenuCommand(10L, "수정 메뉴", "수정 설명", "restaurant-menus/updated.jpg",
                        "JPY", BigDecimal.valueOf(1_500), false),
                new MenuCommand(null, "신규 메뉴", "신규 설명", "restaurant-menus/new.jpg",
                        "JPY", BigDecimal.valueOf(900), true)
        ));

        restaurantService.updateByAdmin(1L, command);

        assertThat(restaurant.getMenus()).hasSize(2);
        assertThat(restaurant.getMenus())
                .filteredOn(menu -> menu.getId() != null)
                .singleElement()
                .satisfies(menu -> {
                    assertThat(menu.getId()).isEqualTo(10L);
                    assertThat(menu.getName()).isEqualTo("수정 메뉴");
                    assertThat(menu.getPriceAmount()).isEqualByComparingTo("1500");
                    assertThat(menu.isMain()).isFalse();
                });
        assertThat(restaurant.getMenus())
                .filteredOn(menu -> menu.getId() == null)
                .singleElement()
                .satisfies(menu -> assertThat(menu.getName()).isEqualTo("신규 메뉴"));
    }

    @Test
    void 어드민_메뉴_수정은_다른_식당의_메뉴_ID를_거부한다() {
        RestaurantService restaurantService = createRestaurantService();
        Restaurant restaurant = createRestaurant(1L, 4.8, 100L);
        restaurant.addMenu(createMenu(10L, "기존 메뉴", true));
        given(restaurantRepository.findByIdForUpdate(1L)).willReturn(Optional.of(restaurant));
        AdminRestaurantCommand command = updateMenuCommand(List.of(
                new MenuCommand(999L, "다른 메뉴", "설명", null,
                        "JPY", BigDecimal.valueOf(1_000), false)
        ));

        assertThatThrownBy(() -> restaurantService.updateByAdmin(1L, command))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(RestaurantErrorCode.MENU_NOT_FOUND));

        assertThat(restaurant.getMenus())
                .extracting(RestaurantMenu::getId)
                .containsExactly(10L);
    }

    @Test
    void 어드민_메뉴_수정은_중복된_메뉴_ID를_거부한다() {
        RestaurantService restaurantService = createRestaurantService();
        Restaurant restaurant = createRestaurant(1L, 4.8, 100L);
        restaurant.addMenu(createMenu(10L, "기존 메뉴", true));
        given(restaurantRepository.findByIdForUpdate(1L)).willReturn(Optional.of(restaurant));
        AdminRestaurantCommand command = updateMenuCommand(List.of(
                new MenuCommand(10L, "첫 번째", "설명", null,
                        "JPY", BigDecimal.valueOf(1_000), false),
                new MenuCommand(10L, "두 번째", "설명", null,
                        "JPY", BigDecimal.valueOf(1_200), true)
        ));

        assertThatThrownBy(() -> restaurantService.updateByAdmin(1L, command))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));
    }

    @Test
    void 어드민_식당_등록은_식당과_메뉴_asset을_한번에_claim한다() {
        RestaurantService restaurantService = createRestaurantService();
        UUID restaurantAssetId = UUID.randomUUID();
        UUID menuAssetId = UUID.randomUUID();
        AdminRestaurantCommand command = createAssetAdminCommand(
                List.of(restaurantAssetId),
                List.of(new MenuCommand(
                        null, "asset 메뉴", "설명", null, menuAssetId,
                        "JPY", BigDecimal.valueOf(1_000), true))
        );
        given(restaurantRepository.save(any(Restaurant.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        restaurantService.createByAdmin(command);

        verify(mediaPort).reconcileBindings(
                argThat(claims -> Set.copyOf(claims).equals(Set.of(
                        new MediaAssetUse(restaurantAssetId, MediaAssetPurpose.RESTAURANT),
                        new MediaAssetUse(menuAssetId, MediaAssetPurpose.RESTAURANT_MENU)
                ))),
                argThat(Collection -> Collection.isEmpty())
        );
        ArgumentCaptor<Restaurant> restaurantCaptor = ArgumentCaptor.forClass(Restaurant.class);
        verify(restaurantRepository).save(restaurantCaptor.capture());
        assertThat(restaurantCaptor.getValue().getImages())
                .extracting(RestaurantImage::getImageAssetId)
                .containsExactly(restaurantAssetId);
        assertThat(restaurantCaptor.getValue().getMenus())
                .extracting(RestaurantMenu::getImageAssetId)
                .containsExactly(menuAssetId);
    }

    @Test
    void 어드민_응답은_식당과_메뉴_이미지를_한번에_bulk_조회한다() {
        RestaurantService restaurantService = createRestaurantService();
        UUID restaurantAssetId = UUID.randomUUID();
        UUID menuAssetId = UUID.randomUUID();
        Restaurant restaurant = createRestaurant(1L, 4.8, 100L);
        restaurant.replaceImages(List.of(image(101L, null, restaurantAssetId, 1)));
        restaurant.addMenu(createAssetMenu(201L, "asset 메뉴", true, menuAssetId));
        given(restaurantRepository.findByIdForUpdate(1L)).willReturn(Optional.of(restaurant));
        MediaImage thumbnail = readyImage(
                restaurantAssetId,
                MediaImageRole.RESTAURANT_THUMBNAIL,
                "https://cdn.example.com/media/admin-thumbnail.webp");
        MediaImage hero = readyImage(
                restaurantAssetId,
                MediaImageRole.RESTAURANT_HERO,
                "https://cdn.example.com/media/admin-hero.webp");
        MediaImage menuList = readyImage(
                menuAssetId,
                MediaImageRole.MENU_LIST,
                "https://cdn.example.com/media/admin-menu.webp");
        given(mediaPort.findImages(any())).willReturn(Map.of(
                new MediaImageRequest(restaurantAssetId, MediaImageRole.RESTAURANT_THUMBNAIL), thumbnail,
                new MediaImageRequest(restaurantAssetId, MediaImageRole.RESTAURANT_HERO), hero,
                new MediaImageRequest(menuAssetId, MediaImageRole.MENU_LIST), menuList
        ));

        var response = restaurantService.updateByAdmin(1L, updateMenuCommand(null));

        assertThat(response.thumbnailUrl()).isEqualTo(thumbnail.defaultSource().url());
        assertThat(response.thumbnailImage().restaurantImageId()).isEqualTo(101L);
        assertThat(response.imageUrls()).containsExactly(hero.defaultSource().url());
        assertThat(response.heroImages()).singleElement()
                .satisfies(image -> assertThat(image.image()).isEqualTo(hero));
        assertThat(response.menus()).singleElement()
                .satisfies(menu -> {
                    assertThat(menu.imageUrl()).isEqualTo(menuList.defaultSource().url());
                    assertThat(menu.listImage()).isEqualTo(menuList);
                });
        verify(mediaPort).findImages(argThat(requests -> Set.copyOf(requests).equals(Set.of(
                new MediaImageRequest(
                        restaurantAssetId, MediaImageRole.RESTAURANT_THUMBNAIL),
                new MediaImageRequest(restaurantAssetId, MediaImageRole.RESTAURANT_HERO),
                new MediaImageRequest(menuAssetId, MediaImageRole.MENU_LIST)
        ))));
        verify(mediaPort, never()).reconcileBindings(any(), any());
        verify(fileStorage, never()).resolveFileUrl(any());
    }

    @Test
    void ordered_wrapper는_기존_association_ID를_유지하고_추가와_제거만_전이한다() {
        RestaurantService restaurantService = createRestaurantService();
        UUID removedAssetId = UUID.randomUUID();
        UUID retainedAssetId = UUID.randomUUID();
        UUID newAssetId = UUID.randomUUID();
        Restaurant restaurant = createRestaurant(1L, 4.8, 100L);
        RestaurantImage removed = image(101L, "restaurants/removed.jpg", removedAssetId, 1);
        RestaurantImage retained = image(102L, "restaurants/retained.jpg", retainedAssetId, 2);
        restaurant.replaceImages(List.of(removed, retained));
        given(restaurantRepository.findByIdForUpdate(1L)).willReturn(Optional.of(restaurant));

        restaurantService.updateByAdmin(1L, updateImagesCommand(
                null,
                List.of(
                        new ImageCommand(102L, null),
                        new ImageCommand(null, newAssetId)
                )
        ));

        assertThat(restaurant.getImages())
                .extracting(RestaurantImage::getId)
                .containsExactly(102L, null);
        assertThat(restaurant.getImages())
                .extracting(RestaurantImage::getDisplayOrder)
                .containsExactly(1, 2);
        verify(mediaPort).reconcileBindings(
                argThat(claims -> List.copyOf(claims).equals(List.of(
                        new MediaAssetUse(newAssetId, MediaAssetPurpose.RESTAURANT)))),
                argThat(retires -> List.copyOf(retires).equals(List.of(
                        new MediaAssetUse(removedAssetId, MediaAssetPurpose.RESTAURANT))))
        );
    }

    @Test
    void ordered_wrapper의_중복_asset은_media_중복_오류를_그대로_반환한다() {
        RestaurantService restaurantService = createRestaurantService();
        UUID duplicateAssetId = UUID.randomUUID();
        Restaurant restaurant = createRestaurant(1L, 4.8, 100L);
        restaurant.replaceImages(List.of(RestaurantImage.createLegacy("restaurants/current.jpg", 1)));
        given(restaurantRepository.findByIdForUpdate(1L)).willReturn(Optional.of(restaurant));
        doThrow(new BusinessException(MediaErrorCode.DUPLICATE_ASSET))
                .when(mediaPort).reconcileBindings(any(), any());

        assertThatThrownBy(() -> restaurantService.updateByAdmin(
                1L,
                updateImagesCommand(null, List.of(
                        new ImageCommand(null, duplicateAssetId),
                        new ImageCommand(null, duplicateAssetId)
                ))
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(MediaErrorCode.DUPLICATE_ASSET));

        assertThat(restaurant.getImages())
                .extracting(RestaurantImage::getFileKey)
                .containsExactly("restaurants/current.jpg");
        verify(restaurantRepository, never()).flush();
    }

    @Test
    void legacy_중복_key_재정렬은_기존_ID와_backfill_asset을_보존한다() {
        RestaurantService restaurantService = createRestaurantService();
        UUID backfilledAssetId = UUID.randomUUID();
        Restaurant restaurant = createRestaurant(1L, 4.8, 100L);
        restaurant.replaceImages(List.of(
                image(101L, "A", backfilledAssetId, 1),
                image(102L, "A", null, 2),
                image(103L, "B", null, 3)
        ));
        given(restaurantRepository.findByIdForUpdate(1L)).willReturn(Optional.of(restaurant));

        restaurantService.updateByAdmin(
                1L,
                updateImagesCommand(List.of("B", "A", "A"), null)
        );

        assertThat(restaurant.getImages())
                .extracting(RestaurantImage::getId)
                .containsExactly(103L, 101L, 102L);
        assertThat(restaurant.getImages().get(1).getImageAssetId())
                .isEqualTo(backfilledAssetId);
        verify(mediaPort, never()).reconcileBindings(any(), any());
    }

    @Test
    void media_binding이_실패하면_Aggregate를_변경하지_않는다() {
        RestaurantService restaurantService = createRestaurantService();
        UUID currentAssetId = UUID.randomUUID();
        UUID newAssetId = UUID.randomUUID();
        Restaurant restaurant = createRestaurant(1L, 4.8, 100L);
        RestaurantImage current = image(101L, null, currentAssetId, 1);
        restaurant.replaceImages(List.of(current));
        given(restaurantRepository.findByIdForUpdate(1L)).willReturn(Optional.of(restaurant));
        doThrow(new BusinessException(CommonErrorCode.INVALID_INPUT))
                .when(mediaPort).reconcileBindings(any(), any());

        assertThatThrownBy(() -> restaurantService.updateByAdmin(
                1L,
                updateImagesCommand(null, List.of(new ImageCommand(null, newAssetId)))
        )).isInstanceOf(BusinessException.class);

        assertThat(restaurant.getImages()).containsExactly(current);
        assertThat(current.getDisplayOrder()).isEqualTo(1);
        verify(restaurantRepository, never()).flush();
    }

    @Test
    void 같은_legacy_메뉴_key는_backfill_asset을_보존하고_media를_변경하지_않는다() {
        RestaurantService restaurantService = createRestaurantService();
        UUID backfilledAssetId = UUID.randomUUID();
        Restaurant restaurant = createRestaurant(1L, 4.8, 100L);
        RestaurantMenu menu = createMenu(10L, "기존 메뉴", true);
        ReflectionTestUtils.setField(menu, "imageAssetId", backfilledAssetId);
        restaurant.addMenu(menu);
        given(restaurantRepository.findByIdForUpdate(1L)).willReturn(Optional.of(restaurant));

        restaurantService.updateByAdmin(1L, updateMenuCommand(List.of(
                new MenuCommand(
                        10L, "수정 메뉴", "설명", menu.getImageKey(),
                        "JPY", BigDecimal.valueOf(1_200), true)
        )));

        assertThat(menu.getImageAssetId()).isEqualTo(backfilledAssetId);
        assertThat(menu.getImageKey()).isEqualTo("restaurant-menus/10.jpg");
        verify(mediaPort, never()).reconcileBindings(any(), any());
    }

    @Test
    void 메뉴_asset_교체는_새_asset만_claim하고_기존_asset만_retire한다() {
        RestaurantService restaurantService = createRestaurantService();
        UUID oldAssetId = UUID.randomUUID();
        UUID newAssetId = UUID.randomUUID();
        Restaurant restaurant = createRestaurant(1L, 4.8, 100L);
        RestaurantMenu menu = createMenu(10L, "기존 메뉴", true);
        ReflectionTestUtils.setField(menu, "imageKey", null);
        ReflectionTestUtils.setField(menu, "imageAssetId", oldAssetId);
        restaurant.addMenu(menu);
        given(restaurantRepository.findByIdForUpdate(1L)).willReturn(Optional.of(restaurant));

        restaurantService.updateByAdmin(1L, new AdminRestaurantCommand(
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null,
                List.of(new MenuCommand(
                        10L, "수정 메뉴", "설명", null, newAssetId,
                        "JPY", BigDecimal.valueOf(1_200), true)),
                null, null, null
        ));

        assertThat(menu.getImageAssetId()).isEqualTo(newAssetId);
        assertThat(menu.getImageKey()).isNull();
        verify(mediaPort).reconcileBindings(
                argThat(claims -> List.copyOf(claims).equals(List.of(
                        new MediaAssetUse(newAssetId, MediaAssetPurpose.RESTAURANT_MENU)))),
                argThat(retires -> List.copyOf(retires).equals(List.of(
                        new MediaAssetUse(oldAssetId, MediaAssetPurpose.RESTAURANT_MENU))))
        );
    }

    @Test
    void 기본값으로_식당_목록을_조회하고_다음_커서를_반환한다() {
        RestaurantService restaurantService = createRestaurantService();
        List<Restaurant> restaurants = LongStream.rangeClosed(1, 11)
                .mapToObj(id -> createRestaurant(id, 4.0, id * 10))
                .toList();
        givenRestaurants(restaurants);
        given(fileStorage.resolveFileUrl(anyString()))
                .willAnswer(invocation -> "https://cdn.example.com/" + invocation.getArgument(0));

        RestaurantListResponse response = restaurantService.getRestaurants(
                null,
                null,
                null,
                null,
                null,
                null
        );

        assertThat(response.content()).hasSize(10);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.nextCursor()).isNotBlank();
        assertThat(response.content().getFirst().thumbnailUrl())
                .isEqualTo("https://cdn.example.com/restaurants/1/thumbnail.jpg");

        RestaurantCursor decodedCursor = RestaurantCursorCodec.decode(response.nextCursor(), RestaurantSort.BASIC);
        assertThat(decodedCursor.id()).isEqualTo(response.content().getLast().restaurantId());

        Pageable pageable = capturePageable();
        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(11);
        assertSortOrder(pageable, "id");
        verify(restaurantRepository).findBusinessHoursByRestaurantIdsAndDayOfWeek(
                LongStream.rangeClosed(1, 10).boxed().toList(),
                DayOfWeek.MONDAY
        );
    }

    @Test
    void 식당_목록은_일본_현지_오늘의_영업시간과_휴무를_구분해_반환한다() {
        RestaurantService restaurantService = createRestaurantService();
        Restaurant openRestaurant = createRestaurant(1L, 4.8, 100L);
        Restaurant closedRestaurant = createRestaurant(2L, 4.7, 90L);
        Restaurant missingRestaurant = createRestaurant(3L, 4.6, 80L);
        RestaurantBusinessHour openBusinessHour = RestaurantBusinessHour.create(
                DayOfWeek.MONDAY,
                LocalTime.of(10, 0),
                LocalTime.of(22, 0),
                null,
                null,
                false
        );
        RestaurantBusinessHour closedBusinessHour = RestaurantBusinessHour.create(
                DayOfWeek.MONDAY,
                null,
                null,
                null,
                null,
                true
        );
        openRestaurant.addBusinessHour(openBusinessHour);
        closedRestaurant.addBusinessHour(closedBusinessHour);
        givenRestaurants(List.of(openRestaurant, closedRestaurant, missingRestaurant));
        given(restaurantRepository.findBusinessHoursByRestaurantIdsAndDayOfWeek(
                List.of(1L, 2L, 3L),
                DayOfWeek.MONDAY
        )).willReturn(List.of(openBusinessHour, closedBusinessHour));

        RestaurantListResponse response = restaurantService.getRestaurants(
                null,
                null,
                null,
                null,
                null,
                10
        );

        assertThat(response.content().get(0).todayBusinessHour().date()).isEqualTo("2026-07-13");
        assertThat(response.content().get(0).todayBusinessHour().dayOfWeek()).isEqualTo("MONDAY");
        assertThat(response.content().get(0).todayBusinessHour().openTime()).isEqualTo("10:00");
        assertThat(response.content().get(0).todayBusinessHour().closeTime()).isEqualTo("22:00");
        assertThat(response.content().get(0).todayBusinessHour().closed()).isFalse();
        assertThat(response.content().get(1).todayBusinessHour().closed()).isTrue();
        assertThat(response.content().get(1).todayBusinessHour().openTime()).isNull();
        assertThat(response.content().get(1).todayBusinessHour().closeTime()).isNull();
        assertThat(response.content().get(2).todayBusinessHour()).isNull();
    }

    @Test
    void 인기순_커서로_식당_목록을_조회한다() {
        RestaurantService restaurantService = createRestaurantService();
        Restaurant cursorBase = createRestaurant(20L, 4.8, 100L);
        String cursor = RestaurantCursorCodec.encode(cursorBase, RestaurantSort.POPULAR);
        givenRestaurants(List.of(createRestaurant(19L, 4.7, 90L)));
        given(fileStorage.resolveFileUrl(anyString()))
                .willAnswer(invocation -> "https://cdn.example.com/" + invocation.getArgument(0));

        RestaurantListResponse response = restaurantService.getRestaurants(
                "스시",
                "sushi",
                "popular",
                "sns-hot",
                cursor,
                20
        );

        assertThat(response.content()).hasSize(1);
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();

        Pageable pageable = capturePageable();
        assertThat(pageable.getPageSize()).isEqualTo(21);
        assertSortOrder(pageable, "reviewCount");
        assertSortOrder(pageable, "rating");
        assertSortOrder(pageable, "id");
    }

    @Test
    void 별점순_조회에서_페이지_크기를_최대값으로_제한한다() {
        RestaurantService restaurantService = createRestaurantService();
        Restaurant cursorBase = createRestaurant(20L, 4.8, 100L);
        String cursor = RestaurantCursorCodec.encode(cursorBase, RestaurantSort.RATING);
        givenRestaurants(List.of());

        RestaurantListResponse response = restaurantService.getRestaurants(
                null,
                "all",
                "rating",
                "all",
                cursor,
                100
        );

        assertThat(response.content()).isEmpty();
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();

        Pageable pageable = capturePageable();
        assertThat(pageable.getPageSize()).isEqualTo(51);
        assertSortOrder(pageable, "rating");
        assertSortOrder(pageable, "id");
        verify(restaurantRepository, never()).findBusinessHoursByRestaurantIdsAndDayOfWeek(any(), any());
    }

    @Test
    void 지원하지_않는_조회_조건이면_예외가_발생한다() {
        RestaurantService restaurantService = createRestaurantService();

        assertThatThrownBy(() -> restaurantService.getRestaurants(
                null,
                "invalid-genre",
                null,
                null,
                null,
                10
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(RestaurantErrorCode.UNSUPPORTED_GENRE));

        assertThatThrownBy(() -> restaurantService.getRestaurants(
                null,
                null,
                "invalid-sort",
                null,
                null,
                10
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(RestaurantErrorCode.UNSUPPORTED_SORT));

        assertThatThrownBy(() -> restaurantService.getRestaurants(
                null,
                null,
                null,
                "invalid-type",
                null,
                10
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(RestaurantErrorCode.UNSUPPORTED_LIST_TYPE));

        assertThatThrownBy(() -> restaurantService.getRestaurants(
                null,
                null,
                "popular",
                null,
                "not-a-valid-cursor",
                10
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));

        verifyNoInteractions(restaurantRepository);
    }

    @Test
    void 식당명과_메뉴명으로_검색_자동완성을_조회한다() {
        RestaurantService restaurantService = createRestaurantService();
        given(restaurantRepository.findRestaurantSuggestionKeywords(anyString(), any(Pageable.class)))
                .willReturn(List.of("히마와리 스시"));
        given(restaurantRepository.findMenuSuggestionKeywords(anyString(), any(Pageable.class)))
                .willReturn(List.of("스시"));

        RestaurantSearchSuggestionResponse response = restaurantService.getSearchSuggestions(" 스시 ", 10);

        assertThat(response.suggestions()).hasSize(2);
        assertThat(response.suggestions().get(0).keyword()).isEqualTo("히마와리 스시");
        assertThat(response.suggestions().get(0).type()).isEqualTo("restaurant");
        assertThat(response.suggestions().get(1).keyword()).isEqualTo("스시");
        assertThat(response.suggestions().get(1).type()).isEqualTo("menu");
    }

    @Test
    void 검색_자동완성은_요청_개수만큼만_반환한다() {
        RestaurantService restaurantService = createRestaurantService();
        given(restaurantRepository.findRestaurantSuggestionKeywords(anyString(), any(Pageable.class)))
                .willReturn(List.of("히마와리 스시", "스시 오마카세"));

        RestaurantSearchSuggestionResponse response = restaurantService.getSearchSuggestions("스시", 2);

        assertThat(response.suggestions()).hasSize(2);
        verify(restaurantRepository).findRestaurantSuggestionKeywords(anyString(), any(Pageable.class));
        verify(restaurantRepository, never())
                .findMenuSuggestionKeywords(anyString(), any(Pageable.class));
    }

    @Test
    void 검색어가_비어있으면_자동완성_조회에_실패한다() {
        RestaurantService restaurantService = createRestaurantService();

        assertThatThrownBy(() -> restaurantService.getSearchSuggestions(" ", 10))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));

        verifyNoInteractions(restaurantRepository);
    }

    @Test
    void 추천_검색어를_조회한다() {
        RestaurantService restaurantService = createRestaurantService();

        RestaurantSearchKeywordRecommendationResponse response =
                restaurantService.getSearchKeywordRecommendations(3);

        assertThat(response.keywords()).containsExactly("스시", "라멘", "야키토리");
        verifyNoInteractions(restaurantRepository);
    }

    @Test
    void 검색_보조_기능은_기본_검색_개수를_사용한다() {
        RestaurantService restaurantService = createRestaurantService();
        given(restaurantRepository.findRestaurantSuggestionKeywords(anyString(), any(Pageable.class)))
                .willReturn(List.of());
        given(restaurantRepository.findMenuSuggestionKeywords(anyString(), any(Pageable.class)))
                .willReturn(List.of());

        RestaurantSearchSuggestionResponse suggestionResponse =
                restaurantService.getSearchSuggestions("스시", null);
        RestaurantSearchKeywordRecommendationResponse recommendationResponse =
                restaurantService.getSearchKeywordRecommendations(null);

        assertThat(suggestionResponse.suggestions()).isEmpty();
        assertThat(recommendationResponse.keywords()).hasSize(10);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(restaurantRepository).findRestaurantSuggestionKeywords(anyString(), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(10);
    }

    @Test
    void 검색_보조_기능은_최대_검색_개수로_제한한다() {
        RestaurantService restaurantService = createRestaurantService();
        List<String> restaurantSuggestions = IntStream.rangeClosed(1, 50)
                .mapToObj(index -> "스시 " + index)
                .toList();
        given(restaurantRepository.findRestaurantSuggestionKeywords(anyString(), any(Pageable.class)))
                .willReturn(restaurantSuggestions);

        RestaurantSearchSuggestionResponse suggestionResponse =
                restaurantService.getSearchSuggestions("스시", 100);
        RestaurantSearchKeywordRecommendationResponse recommendationResponse =
                restaurantService.getSearchKeywordRecommendations(100);

        assertThat(suggestionResponse.suggestions()).hasSize(50);
        assertThat(recommendationResponse.keywords()).hasSize(10);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(restaurantRepository).findRestaurantSuggestionKeywords(anyString(), pageableCaptor.capture());
        verify(restaurantRepository, never())
                .findMenuSuggestionKeywords(anyString(), any(Pageable.class));
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(50);
    }

    @Test
    void 검색_보조_기능은_검색_개수가_1보다_작으면_실패한다() {
        RestaurantService restaurantService = createRestaurantService();

        assertThatThrownBy(() -> restaurantService.getSearchSuggestions("스시", 0))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));
        assertThatThrownBy(() -> restaurantService.getSearchKeywordRecommendations(0))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));

        verifyNoInteractions(restaurantRepository);
    }

    @Test
    void getRestaurantSummary_returns_main_information() {
        RestaurantService restaurantService = createRestaurantService();
        Restaurant restaurant = createRestaurant(1L, 4.8, 100L);
        ReflectionTestUtils.setField(restaurant, "reviewCount", 256L);
        restaurant.replaceImages(List.of(
                image(101L, "restaurants/1/images/1.jpg", null, 1),
                image(102L, "restaurants/1/images/2.jpg", null, 2)
        ));
        given(restaurantRepository.findActiveByIdWithImages(1L)).willReturn(Optional.of(restaurant));
        given(fileStorage.resolveFileUrl("restaurants/1/images/1.jpg"))
                .willReturn("https://cdn.example.com/restaurants/1/images/1.jpg");
        given(fileStorage.resolveFileUrl("restaurants/1/images/2.jpg"))
                .willReturn("https://cdn.example.com/restaurants/1/images/2.jpg");

        var response = restaurantService.getRestaurantSummary(1L);

        assertThat(response.restaurantId()).isEqualTo(1L);
        assertThat(response.rating()).isEqualByComparingTo("4.8");
        assertThat(response.reviewCount()).isEqualTo(256L);
        assertThat(response.foodCategory()).isEqualTo("초밥");
        assertThat(response.thumbnailUrl()).isEqualTo("https://cdn.example.com/restaurants/1/images/1.jpg");
        assertThat(response.imageUrls()).containsExactly(
                "https://cdn.example.com/restaurants/1/images/1.jpg",
                "https://cdn.example.com/restaurants/1/images/2.jpg"
        );
        assertThat(response.thumbnailImage().restaurantImageId()).isEqualTo(101L);
        assertThat(response.thumbnailImage().image()).isNull();
        assertThat(response.heroImages())
                .extracting(image -> image.restaurantImageId(), image -> image.displayOrder())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(101L, 1),
                        org.assertj.core.groups.Tuple.tuple(102L, 2));
        assertThat(response.heroImages())
                .allSatisfy(image -> assertThat(image.image()).isNull());
        assertThat(response.reservationFee()).isEqualTo(4_000L);
        verifyNoInteractions(mediaPort);
    }

    @Test
    void media_READY_식당은_role별_URL과_신규_이미지_객체를_반환한다() {
        RestaurantService restaurantService = createRestaurantService();
        UUID assetId = UUID.randomUUID();
        Restaurant restaurant = createRestaurant(1L, 4.8, 100L);
        restaurant.replaceImages(List.of(
                image(101L, "restaurants/1/legacy.jpg", assetId, 1)
        ));
        MediaImage thumbnail = readyImage(
                assetId,
                MediaImageRole.RESTAURANT_THUMBNAIL,
                "https://cdn.example.com/media/thumbnail.webp");
        MediaImage hero = readyImage(
                assetId,
                MediaImageRole.RESTAURANT_HERO,
                "https://cdn.example.com/media/hero.webp");
        given(restaurantRepository.findActiveByIdWithImages(1L)).willReturn(Optional.of(restaurant));
        given(mediaPort.findImages(any())).willReturn(Map.of(
                new MediaImageRequest(assetId, MediaImageRole.RESTAURANT_THUMBNAIL), thumbnail,
                new MediaImageRequest(assetId, MediaImageRole.RESTAURANT_HERO), hero
        ));

        var response = restaurantService.getRestaurantSummary(1L);

        assertThat(response.thumbnailUrl()).isEqualTo(thumbnail.defaultSource().url());
        assertThat(response.thumbnailImage().restaurantImageId()).isEqualTo(101L);
        assertThat(response.thumbnailImage().image()).isEqualTo(thumbnail);
        assertThat(response.imageUrls()).containsExactly(hero.defaultSource().url());
        assertThat(response.heroImages()).singleElement()
                .satisfies(image -> assertThat(image.image()).isEqualTo(hero));
        verify(mediaPort).findImages(argThat(requests -> Set.copyOf(requests).equals(Set.of(
                new MediaImageRequest(assetId, MediaImageRole.RESTAURANT_THUMBNAIL),
                new MediaImageRequest(assetId, MediaImageRole.RESTAURANT_HERO)
        ))));
        verify(fileStorage, never()).resolveFileUrl(any());
    }

    @Test
    void media_PROCESSING과_FAILED는_legacy_URL로_우회하지_않고_슬롯을_유지한다() {
        RestaurantService restaurantService = createRestaurantService();
        UUID processingAssetId = UUID.randomUUID();
        UUID failedAssetId = UUID.randomUUID();
        Restaurant restaurant = createRestaurant(1L, 4.8, 100L);
        restaurant.replaceImages(List.of(
                image(101L, "restaurants/1/processing.jpg", processingAssetId, 1),
                image(102L, "restaurants/1/failed.jpg", failedAssetId, 2)
        ));
        given(restaurantRepository.findActiveByIdWithImages(1L)).willReturn(Optional.of(restaurant));
        given(mediaPort.findImages(any())).willReturn(Map.of(
                new MediaImageRequest(processingAssetId, MediaImageRole.RESTAURANT_THUMBNAIL),
                statusImage(
                        processingAssetId,
                        MediaImageRole.RESTAURANT_THUMBNAIL,
                        MediaImageStatus.PROCESSING),
                new MediaImageRequest(processingAssetId, MediaImageRole.RESTAURANT_HERO),
                statusImage(
                        processingAssetId,
                        MediaImageRole.RESTAURANT_HERO,
                        MediaImageStatus.PROCESSING),
                new MediaImageRequest(failedAssetId, MediaImageRole.RESTAURANT_HERO),
                statusImage(
                        failedAssetId,
                        MediaImageRole.RESTAURANT_HERO,
                        MediaImageStatus.FAILED)
        ));

        var response = restaurantService.getRestaurantSummary(1L);

        assertThat(response.thumbnailUrl()).isNull();
        assertThat(response.thumbnailImage().image().status())
                .isEqualTo(MediaImageStatus.PROCESSING);
        assertThat(response.imageUrls()).isEmpty();
        assertThat(response.heroImages())
                .extracting(image -> image.restaurantImageId(), image -> image.image().status())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(101L, MediaImageStatus.PROCESSING),
                        org.assertj.core.groups.Tuple.tuple(102L, MediaImageStatus.FAILED));
        verify(fileStorage, never()).resolveFileUrl(any());
    }

    @Test
    void media_asset_조회가_불일치해도_legacy_URL로_우회하지_않는다() {
        RestaurantService restaurantService = createRestaurantService();
        UUID assetId = UUID.randomUUID();
        Restaurant restaurant = createRestaurant(1L, 4.8, 100L);
        restaurant.replaceImages(List.of(
                image(101L, "restaurants/1/backfilled.jpg", assetId, 1)
        ));
        given(restaurantRepository.findActiveByIdWithImages(1L)).willReturn(Optional.of(restaurant));
        given(mediaPort.findImages(any())).willReturn(Map.of());

        var response = restaurantService.getRestaurantSummary(1L);

        assertThat(response.thumbnailUrl()).isNull();
        assertThat(response.thumbnailImage().image()).isNull();
        assertThat(response.imageUrls()).isEmpty();
        assertThat(response.heroImages()).singleElement()
                .satisfies(image -> {
                    assertThat(image.restaurantImageId()).isEqualTo(101L);
                    assertThat(image.image()).isNull();
                });
        verify(fileStorage, never()).resolveFileUrl(any());
    }

    @Test
    void 식당_목록은_페이지에_포함된_asset만_한번에_bulk_조회한다() {
        RestaurantService restaurantService = createRestaurantService();
        UUID firstAssetId = UUID.randomUUID();
        UUID secondAssetId = UUID.randomUUID();
        UUID excludedAssetId = UUID.randomUUID();
        Restaurant first = createRestaurant(1L, 4.8, 100L);
        Restaurant second = createRestaurant(2L, 4.7, 90L);
        Restaurant excluded = createRestaurant(3L, 4.6, 80L);
        first.replaceImages(List.of(image(101L, null, firstAssetId, 1)));
        second.replaceImages(List.of(image(201L, null, secondAssetId, 1)));
        excluded.replaceImages(List.of(image(301L, null, excludedAssetId, 1)));
        givenRestaurants(List.of(first, second, excluded));
        given(mediaPort.findImages(any())).willReturn(Map.of(
                new MediaImageRequest(firstAssetId, MediaImageRole.RESTAURANT_THUMBNAIL),
                readyImage(firstAssetId, MediaImageRole.RESTAURANT_THUMBNAIL, "https://cdn/1-thumb.webp"),
                new MediaImageRequest(firstAssetId, MediaImageRole.RESTAURANT_CARD),
                readyImage(firstAssetId, MediaImageRole.RESTAURANT_CARD, "https://cdn/1-card.webp"),
                new MediaImageRequest(secondAssetId, MediaImageRole.RESTAURANT_THUMBNAIL),
                readyImage(secondAssetId, MediaImageRole.RESTAURANT_THUMBNAIL, "https://cdn/2-thumb.webp"),
                new MediaImageRequest(secondAssetId, MediaImageRole.RESTAURANT_CARD),
                readyImage(secondAssetId, MediaImageRole.RESTAURANT_CARD, "https://cdn/2-card.webp")
        ));

        RestaurantListResponse response = restaurantService.getRestaurants(
                null, null, null, null, null, 2);

        assertThat(response.content()).hasSize(2);
        assertThat(response.content().getFirst().thumbnailUrl())
                .isEqualTo("https://cdn/1-thumb.webp");
        assertThat(response.content().getFirst().imageUrls())
                .containsExactly("https://cdn/1-card.webp");
        verify(mediaPort).findImages(argThat(requests -> Set.copyOf(requests).equals(Set.of(
                new MediaImageRequest(firstAssetId, MediaImageRole.RESTAURANT_THUMBNAIL),
                new MediaImageRequest(firstAssetId, MediaImageRole.RESTAURANT_CARD),
                new MediaImageRequest(secondAssetId, MediaImageRole.RESTAURANT_THUMBNAIL),
                new MediaImageRequest(secondAssetId, MediaImageRole.RESTAURANT_CARD)
        ))));
    }

    @Test
    void 랜덤_추천은_현재_식당을_제외하고_메인_정보를_반환한다() {
        RestaurantService restaurantService = createRestaurantService();
        Restaurant restaurant = createRestaurant(2L, 4.8, 100L);
        given(restaurantRepository.findRandomRestaurantIdExcluding(1L)).willReturn(Optional.of(2L));
        given(restaurantRepository.findActiveByIdWithImages(2L)).willReturn(Optional.of(restaurant));
        given(fileStorage.resolveFileUrl("restaurants/2/thumbnail.jpg"))
                .willReturn("https://cdn.example.com/restaurants/2/thumbnail.jpg");

        var response = restaurantService.getRandomRestaurantRecommendation(1L);

        assertThat(response.restaurantId()).isEqualTo(2L);
        assertThat(response.thumbnailUrl()).isEqualTo("https://cdn.example.com/restaurants/2/thumbnail.jpg");
        verify(restaurantRepository).findRandomRestaurantIdExcluding(1L);
        verify(restaurantRepository).findActiveByIdWithImages(2L);
    }

    @Test
    void 랜덤_추천_후보가_없으면_도메인_예외가_발생한다() {
        RestaurantService restaurantService = createRestaurantService();
        given(restaurantRepository.findRandomRestaurantIdExcluding(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> restaurantService.getRandomRestaurantRecommendation(1L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(RestaurantErrorCode.RECOMMENDATION_NOT_FOUND));

        verify(restaurantRepository, never()).findActiveByIdWithImages(any());
    }

    @Test
    void getStoreInformation_returns_business_hours_and_price_range() {
        RestaurantService restaurantService = createRestaurantService();
        Restaurant restaurant = createRestaurant(1L, 4.8, 100L);
        restaurant.replaceBusinessHours(List.of(
                RestaurantBusinessHour.create(DayOfWeek.TUESDAY, LocalTime.of(11, 0),
                        LocalTime.of(21, 0), LocalTime.of(15, 0), LocalTime.of(16, 0), false),
                RestaurantBusinessHour.create(DayOfWeek.MONDAY, LocalTime.of(10, 0),
                        LocalTime.of(22, 0), LocalTime.of(14, 30), LocalTime.of(15, 30), false)
        ));
        given(restaurantRepository.findActiveByIdWithBusinessHours(1L)).willReturn(Optional.of(restaurant));

        RestaurantStoreInformationResponse response = restaurantService.getStoreInformation(1L);

        assertThat(response.restaurantId()).isEqualTo(1L);
        assertThat(response.description()).isEqualTo("매장 상세 설명");
        assertThat(response.businessHours()).hasSize(2);
        assertThat(response.businessHours().getFirst().dayOfWeek()).isEqualTo("MONDAY");
        assertThat(response.businessHours().getFirst().openTime()).isEqualTo("10:00");
        assertThat(response.businessHours().getFirst().breakStart()).isEqualTo("14:30");
        assertThat(response.businessHours().getFirst().breakEnd()).isEqualTo("15:30");
        assertThat(response.priceRange().currency()).isEqualTo("JPY");
        assertThat(response.priceRange().minPrice()).isEqualTo(1000L);
        assertThat(response.priceRange().maxPrice()).isEqualTo(3000L);
    }

    @Test
    void getStoreInformation_returns_description() {
        RestaurantService restaurantService = createRestaurantService();
        Restaurant restaurant = Restaurant.create(
                "Himawari Sushi",
                "Himawari Sushi",
                "restaurant summary",
                "restaurant description",
                "Tokyo",
                "Tokyo",
                RestaurantGenre.SUSHI,
                "초밥",
                PriceCurrency.JPY,
                BigDecimal.valueOf(1000),
                BigDecimal.valueOf(3000)
        );
        ReflectionTestUtils.setField(restaurant, "id", 2L);
        given(restaurantRepository.findActiveByIdWithBusinessHours(2L)).willReturn(Optional.of(restaurant));

        RestaurantStoreInformationResponse response = restaurantService.getStoreInformation(2L);

        assertThat(response.description()).isEqualTo("restaurant description");
    }

    @Test
    void getRestaurantMenus_returns_cursor_page() {
        RestaurantService restaurantService = createRestaurantService();
        List<RestaurantMenu> menus = List.of(
                createMenu(30L, "Omakase Sushi", true),
                createMenu(20L, "Salmon Nigiri", false),
                createMenu(10L, "Tuna Roll", false)
        );
        given(restaurantRepository.existsByIdAndDeletedFalse(1L)).willReturn(true);
        given(restaurantRepository.findMenusByRestaurantId(
                ArgumentMatchers.eq(1L),
                ArgumentMatchers.eq(99L),
                ArgumentMatchers.<Boolean>isNull(),
                ArgumentMatchers.<String>isNull(),
                ArgumentMatchers.<Long>isNull(),
                any(Pageable.class)
        )).willReturn(menus);
        given(fileStorage.resolveFileUrl(anyString()))
                .willAnswer(invocation -> "https://cdn.example.com/" + invocation.getArgument(0));

        RestaurantMenuListResponse response = restaurantService.getRestaurantMenus(1L, 99L, null, 2);

        assertThat(response.content()).hasSize(2);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.nextCursor()).isEqualTo(20L);
        assertThat(response.content().getFirst().menuId()).isEqualTo(30L);
        assertThat(response.content().getFirst().imageUrl())
                .isEqualTo("https://cdn.example.com/restaurant-menus/30.jpg");
    }

    @Test
    void 메뉴_목록_커서_ID에서_복합_정렬_기준을_복원한다() {
        RestaurantService restaurantService = createRestaurantService();
        RestaurantMenu cursorMenu = createMenu(20L, "나 대표 메뉴", true);
        RestaurantMenu nextMenu = createMenu(10L, "일반 메뉴", false);
        given(restaurantRepository.existsByIdAndDeletedFalse(1L)).willReturn(true);
        given(restaurantRepository.findMenuByRestaurantIdAndMenuId(1L, 20L))
                .willReturn(Optional.of(cursorMenu));
        given(restaurantRepository.findMenusByRestaurantId(
                ArgumentMatchers.eq(1L),
                ArgumentMatchers.isNull(),
                ArgumentMatchers.eq(true),
                ArgumentMatchers.eq("나 대표 메뉴"),
                ArgumentMatchers.eq(20L),
                any(Pageable.class)
        )).willReturn(List.of(nextMenu));
        given(fileStorage.resolveFileUrl(anyString()))
                .willAnswer(invocation -> "https://cdn.example.com/" + invocation.getArgument(0));

        RestaurantMenuListResponse response = restaurantService.getRestaurantMenus(1L, null, 20L, 2);

        assertThat(response.content())
                .extracting(RestaurantMenuListResponse.RestaurantMenuResponse::menuId)
                .containsExactly(10L);
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    void 다른_식당의_메뉴_ID를_커서로_사용하면_잘못된_요청을_반환한다() {
        RestaurantService restaurantService = createRestaurantService();
        given(restaurantRepository.existsByIdAndDeletedFalse(1L)).willReturn(true);
        given(restaurantRepository.findMenuByRestaurantIdAndMenuId(1L, 999L))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> restaurantService.getRestaurantMenus(1L, null, 999L, 2))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));

        verify(restaurantRepository, never()).findMenusByRestaurantId(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void 메뉴_상세와_다른_메뉴_개수를_조회한다() {
        RestaurantService restaurantService = createRestaurantService();
        RestaurantMenu menu = createMenu(10L, "Shio Ramen", true);
        given(restaurantRepository.findMenuByRestaurantIdAndMenuId(1L, 10L))
                .willReturn(Optional.of(menu));
        given(restaurantRepository.countOtherMenusByRestaurantId(1L, 10L)).willReturn(6L);
        given(fileStorage.resolveFileUrl("restaurant-menus/10.jpg"))
                .willReturn("https://cdn.example.com/restaurant-menus/10.jpg");

        RestaurantMenuDetailResponse response = restaurantService.getRestaurantMenu(1L, 10L);

        assertThat(response.menuId()).isEqualTo(10L);
        assertThat(response.name()).isEqualTo("Shio Ramen");
        assertThat(response.imageUrl()).isEqualTo("https://cdn.example.com/restaurant-menus/10.jpg");
        assertThat(response.currency()).isEqualTo("JPY");
        assertThat(response.price()).isEqualTo(1_200L);
        assertThat(response.main()).isTrue();
        assertThat(response.otherMenuCount()).isEqualTo(6L);
        verify(restaurantRepository, never()).existsByIdAndDeletedFalse(any());
    }

    @Test
    void media_메뉴_목록은_MENU_LIST_role을_bulk_조회한다() {
        RestaurantService restaurantService = createRestaurantService();
        UUID assetId = UUID.randomUUID();
        RestaurantMenu menu = createAssetMenu(10L, "Shio Ramen", true, assetId);
        MediaImage listImage = readyImage(
                assetId, MediaImageRole.MENU_LIST, "https://cdn.example.com/media/menu-list.webp");
        given(restaurantRepository.existsByIdAndDeletedFalse(1L)).willReturn(true);
        given(restaurantRepository.findMenusByRestaurantId(
                ArgumentMatchers.eq(1L),
                ArgumentMatchers.isNull(),
                ArgumentMatchers.<Boolean>isNull(),
                ArgumentMatchers.<String>isNull(),
                ArgumentMatchers.<Long>isNull(),
                any(Pageable.class)
        )).willReturn(List.of(menu));
        given(mediaPort.findImages(any())).willReturn(Map.of(
                new MediaImageRequest(assetId, MediaImageRole.MENU_LIST), listImage));

        RestaurantMenuListResponse response = restaurantService.getRestaurantMenus(
                1L, null, null, 10);

        assertThat(response.content()).singleElement()
                .satisfies(item -> {
                    assertThat(item.imageUrl()).isEqualTo(listImage.defaultSource().url());
                    assertThat(item.listImage()).isEqualTo(listImage);
                });
        verify(mediaPort).findImages(argThat(requests -> List.copyOf(requests).equals(List.of(
                new MediaImageRequest(assetId, MediaImageRole.MENU_LIST)))));
    }

    @Test
    void media_메뉴_상세_PROCESSING은_원본_URL로_우회하지_않는다() {
        RestaurantService restaurantService = createRestaurantService();
        UUID assetId = UUID.randomUUID();
        RestaurantMenu menu = createAssetMenu(10L, "Shio Ramen", true, assetId);
        ReflectionTestUtils.setField(menu, "imageKey", "restaurant-menus/legacy.jpg");
        MediaImage processing = statusImage(
                assetId, MediaImageRole.MENU_DETAIL, MediaImageStatus.PROCESSING);
        given(restaurantRepository.findMenuByRestaurantIdAndMenuId(1L, 10L))
                .willReturn(Optional.of(menu));
        given(restaurantRepository.countOtherMenusByRestaurantId(1L, 10L)).willReturn(6L);
        given(mediaPort.findImages(any())).willReturn(Map.of(
                new MediaImageRequest(assetId, MediaImageRole.MENU_DETAIL), processing));

        RestaurantMenuDetailResponse response = restaurantService.getRestaurantMenu(1L, 10L);

        assertThat(response.imageUrl()).isNull();
        assertThat(response.detailImage()).isEqualTo(processing);
        verify(mediaPort).findImages(argThat(requests -> List.copyOf(requests).equals(List.of(
                new MediaImageRequest(assetId, MediaImageRole.MENU_DETAIL)))));
        verify(fileStorage, never()).resolveFileUrl(any());
    }

    @Test
    void 존재하는_식당에서_메뉴를_찾을_수_없으면_메뉴_도메인_에러를_반환한다() {
        RestaurantService restaurantService = createRestaurantService();
        given(restaurantRepository.findMenuByRestaurantIdAndMenuId(1L, 999L)).willReturn(Optional.empty());
        given(restaurantRepository.existsByIdAndDeletedFalse(1L)).willReturn(true);

        assertThatThrownBy(() -> restaurantService.getRestaurantMenu(1L, 999L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(RestaurantErrorCode.MENU_NOT_FOUND));

        verify(restaurantRepository, never()).countOtherMenusByRestaurantId(any(), any());
    }

    @Test
    void getRestaurantDetail_throws_not_found_when_restaurant_is_inactive_or_missing() {
        RestaurantService restaurantService = createRestaurantService();
        given(restaurantRepository.findActiveByIdWithImages(404L)).willReturn(Optional.empty());
        given(restaurantRepository.existsByIdAndDeletedFalse(404L)).willReturn(false);
        given(restaurantRepository.findActiveByIdWithBusinessHours(404L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> restaurantService.getRestaurantSummary(404L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(RestaurantErrorCode.NOT_FOUND));

        assertThatThrownBy(() -> restaurantService.getStoreInformation(404L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(RestaurantErrorCode.NOT_FOUND));

        assertThatThrownBy(() -> restaurantService.getRestaurantMenus(404L, null, null, 10))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(RestaurantErrorCode.NOT_FOUND));

        given(restaurantRepository.findMenuByRestaurantIdAndMenuId(404L, 10L)).willReturn(Optional.empty());
        assertThatThrownBy(() -> restaurantService.getRestaurantMenu(404L, 10L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(RestaurantErrorCode.NOT_FOUND));
    }

    private void givenRestaurants(List<Restaurant> restaurants) {
        given(restaurantRepository.findAll(
                ArgumentMatchers.<Specification<Restaurant>>any(),
                any(Pageable.class)
        )).willReturn(new PageImpl<>(restaurants));
    }

    private Pageable capturePageable() {
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(restaurantRepository).findAll(
                ArgumentMatchers.<Specification<Restaurant>>any(),
                pageableCaptor.capture()
        );
        return pageableCaptor.getValue();
    }

    private void assertSortOrder(Pageable pageable, String property) {
        Sort.Order order = pageable.getSort().getOrderFor(property);
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    private Restaurant createRestaurant(Long id, double rating, long reviewCount) {
        Restaurant restaurant = Restaurant.create(
                "히마와리 스시",
                "Himawari Sushi",
                "식당 소개",
                "매장 상세 설명",
                "도쿄도 신주쿠구",
                "도쿄",
                RestaurantGenre.SUSHI,
                "초밥",
                PriceCurrency.JPY,
                BigDecimal.valueOf(1000),
                BigDecimal.valueOf(3000)
        );
        restaurant.replaceImages(List.of(
                RestaurantImage.create("restaurants/%d/thumbnail.jpg".formatted(id), 1)));
        restaurant.replaceHashtags(List.of("예약가능", "스시"));
        ReflectionTestUtils.setField(restaurant, "id", id);
        ReflectionTestUtils.setField(restaurant, "rating", BigDecimal.valueOf(rating));
        ReflectionTestUtils.setField(restaurant, "reviewCount", reviewCount);
        return restaurant;
    }

    private AdminRestaurantCommand createAdminCommand(
            List<String> imageKeys,
            List<String> hashtags
    ) {
        return new AdminRestaurantCommand(
                "히마와리 스시",
                "Himawari Sushi",
                "식당 소개",
                "매장 상세 설명",
                "도쿄도 신주쿠구",
                "도쿄",
                "sushi",
                "sushi",
                "JPY",
                BigDecimal.valueOf(1000),
                BigDecimal.valueOf(3000),
                imageKeys,
                List.of(),
                hashtags,
                List.of(),
                null
        );
    }

    private AdminRestaurantCommand updateMenuCommand(List<MenuCommand> menus) {
        return new AdminRestaurantCommand(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                menus,
                null,
                null,
                null
        );
    }

    private AdminRestaurantCommand createAssetAdminCommand(
            List<UUID> imageAssetIds,
            List<MenuCommand> menus
    ) {
        return new AdminRestaurantCommand(
                "히마와리 스시",
                "Himawari Sushi",
                "식당 소개",
                "매장 상세 설명",
                "도쿄도 신주쿠구",
                "도쿄",
                "sushi",
                "sushi",
                "JPY",
                BigDecimal.valueOf(1_000),
                BigDecimal.valueOf(3_000),
                null,
                imageAssetIds,
                null,
                menus,
                List.of("스시"),
                List.of(),
                java.util.Arrays.stream(DayOfWeek.values())
                        .map(day -> new AdminRestaurantCommand.BusinessHourCommand(
                                day, null, null, null, null, true))
                        .toList()
        );
    }

    private AdminRestaurantCommand updateImagesCommand(
            List<String> imageKeys,
            List<ImageCommand> images
    ) {
        return new AdminRestaurantCommand(
                null, null, null, null, null, null, null, null, null, null, null,
                imageKeys, null, images, null, null, null, null
        );
    }

    private RestaurantImage image(
            Long id,
            String fileKey,
            UUID assetId,
            int displayOrder
    ) {
        RestaurantImage image;
        if (fileKey != null && assetId != null) {
            image = RestaurantImage.createBackfilled(fileKey, assetId, displayOrder);
        } else if (assetId != null) {
            image = RestaurantImage.createAsset(assetId, displayOrder);
        } else {
            image = RestaurantImage.createLegacy(fileKey, displayOrder);
        }
        ReflectionTestUtils.setField(image, "id", id);
        return image;
    }

    private RestaurantMenu createMenu(Long id, String name, boolean representative) {
        RestaurantMenu menu = RestaurantMenu.create(
                name,
                "menu description",
                "restaurant-menus/%d.jpg".formatted(id),
                PriceCurrency.JPY,
                BigDecimal.valueOf(1200),
                representative
        );
        ReflectionTestUtils.setField(menu, "id", id);
        return menu;
    }

    private RestaurantMenu createAssetMenu(
            Long id,
            String name,
            boolean representative,
            UUID assetId
    ) {
        RestaurantMenu menu = RestaurantMenu.createWithAsset(
                name,
                "menu description",
                assetId,
                PriceCurrency.JPY,
                BigDecimal.valueOf(1200),
                representative
        );
        ReflectionTestUtils.setField(menu, "id", id);
        return menu;
    }

    private MediaImage readyImage(UUID assetId, MediaImageRole role, String url) {
        MediaImage.Source source = new MediaImage.Source(url, 270, 270, "image/webp");
        return new MediaImage(
                assetId,
                role,
                MediaImageStatus.READY,
                source,
                List.of(new MediaImage.SourceSet(
                        "image/webp",
                        List.of(new MediaImage.Candidate(url, 270, 270))
                ))
        );
    }

    private MediaImage statusImage(
            UUID assetId,
            MediaImageRole role,
            MediaImageStatus status
    ) {
        return new MediaImage(assetId, role, status, null, List.of());
    }
}
