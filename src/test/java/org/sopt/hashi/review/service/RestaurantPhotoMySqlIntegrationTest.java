package org.sopt.hashi.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.restaurant.domain.PriceCurrency;
import org.sopt.hashi.restaurant.domain.Restaurant;
import org.sopt.hashi.restaurant.domain.RestaurantGenre;
import org.sopt.hashi.restaurant.domain.RestaurantImage;
import org.sopt.hashi.restaurant.domain.RestaurantMenu;
import org.sopt.hashi.restaurant.domain.RestaurantPlaceType;
import org.sopt.hashi.restaurant.domain.RestaurantRepository;
import org.sopt.hashi.review.domain.RestaurantPhotoFilter;
import org.sopt.hashi.review.domain.Review;
import org.sopt.hashi.review.domain.ReviewImage;
import org.sopt.hashi.review.domain.ReviewPhotoRepository;
import org.sopt.hashi.review.domain.ReviewRepository;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.storage.FileStorage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ReviewPhotoRepository.class)
@TestPropertySource(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RestaurantPhotoMySqlIntegrationTest {
    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    @Autowired RestaurantRepository restaurants;
    @Autowired ReviewRepository reviews;
    @Autowired ReviewPhotoRepository photos;
    @Autowired JdbcTemplate jdbc;
    private RestaurantPhotoService service;
    private Restaurant restaurant;

    @BeforeEach
    void setUp() {
        restaurant = restaurants.saveAndFlush(Restaurant.create("photo fixture", "fixture", "fixture", "fixture",
                "synthetic address", "Tokyo", RestaurantGenre.NOODLE, "fixture", RestaurantPlaceType.RESTAURANT,
                PriceCurrency.JPY, BigDecimal.ONE, BigDecimal.TEN));
        RestaurantPort port = mock(RestaurantPort.class);
        when(port.existsActiveById(restaurant.getId())).thenAnswer(call -> restaurants.existsByIdAndDeletedFalse(restaurant.getId()));
        when(port.findRepresentativePhotoSources(restaurant.getId())).thenAnswer(call -> restaurants.findRepresentativePhotoSources(restaurant.getId()));
        when(port.findMenuPhotoSources(restaurant.getId())).thenAnswer(call -> restaurants.findMenuPhotoSources(restaurant.getId()));
        FileStorage storage = mock(FileStorage.class);
        when(storage.resolveFileUrl(anyString())).thenAnswer(call -> "https://images.test/" + call.getArgument(0));
        service = new RestaurantPhotoService(port, photos, mock(MediaPort.class), storage);
    }

    @Test
    void 전체_페이지에서_원본_중복없이_대표1_리뷰3_후_메뉴를_반환한다() {
        restaurant.replaceImages(List.of(RestaurantImage.create("same", 1), RestaurantImage.create("hero2", 2)));
        restaurant.addMenu(RestaurantMenu.create("menu", "fixture", "same", PriceCurrency.JPY, BigDecimal.ONE, false));
        restaurant.addMenu(RestaurantMenu.create("menu2", "fixture", "menu-only", PriceCurrency.JPY, BigDecimal.ONE, false));
        restaurants.flush();
        List<String> reviewKeys = new ArrayList<>();
        reviewKeys.add("same");
        for (int i = 0; i < 43; i++) {
            reviewKeys.add("review-" + i);
        }
        reviewKeys.add("review-0");
        createReviewPages(reviewKeys);
        createReview(List.of("deleted-photo"), true);
        var first = service.getPhotos(restaurant.getId(), RestaurantPhotoFilter.ALL, null);
        assertThat(first.counts().representative()).isEqualTo(2);
        assertThat(first.counts().menu()).isEqualTo(2);
        assertThat(first.counts().review()).isEqualTo(45);
        assertThat(first.counts().all()).isEqualTo(46);
        assertThat(first.photos()).hasSize(20);
        assertThat(first.photos().subList(0, 8)).extracting(photo -> photo.imageUrl()).containsExactly(
                "https://images.test/same", "https://images.test/review-0", "https://images.test/review-1",
                "https://images.test/review-2", "https://images.test/hero2", "https://images.test/review-3",
                "https://images.test/review-4", "https://images.test/review-5");
        var second = service.getPhotos(restaurant.getId(), RestaurantPhotoFilter.ALL, first.nextCursor());
        var third = service.getPhotos(restaurant.getId(), RestaurantPhotoFilter.ALL, second.nextCursor());
        List<String> urls = new ArrayList<>();
        for (var page : List.of(first, second, third)) {
            page.photos().forEach(photo -> urls.add(photo.imageUrl()));
        }
        assertThat(urls).hasSize(46).doesNotHaveDuplicates();
        assertThat(urls.getLast()).isEqualTo("https://images.test/menu-only");
        assertThat(third.hasNext()).isFalse();
        assertThat(third.nextCursor()).isNull();
        assertThat(service.getPhotos(restaurant.getId(), RestaurantPhotoFilter.REVIEW, null).totalCount()).isEqualTo(45);
    }

    @Test
    void 최신_리뷰와_동일시각_ID_순서_및_대소문자가_다른_원본을_보존한다() {
        Review old = createReview(List.of("A", "B"), false);
        Review newer = createReview(List.of("a", "newer"), false);
        jdbc.update("update review set created_at = '2026-01-01 00:00:00' where id in (?,?)", old.getId(), newer.getId());
        assertThat(photos.find(restaurant.getId(), true, List.of(), null, 20))
                .extracting(ReviewPhotoRepository.Photo::fileKey).containsExactly("a", "newer", "A", "B");
        assertThat(photos.count(restaurant.getId(), true, List.of("a"))).isEqualTo(3);
        assertThat(photos.findExistingKeys(restaurant.getId(), List.of("A", "missing"))).containsExactly("A");
        newer.softDelete();
        reviews.flush();
        assertThat(service.getPhotos(restaurant.getId(), RestaurantPhotoFilter.REVIEW, null).totalCount()).isEqualTo(2);
    }

    @Test
    void 앞페이지_사진을_삭제하고_새리뷰가_추가되어도_다음_정렬키부터_혼합을_계속한다() {
        List<RestaurantImage> representative = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            representative.add(RestaurantImage.create("hero-" + i, i + 1));
        }
        restaurant.replaceImages(representative);
        restaurant.addMenu(RestaurantMenu.create("menu", "fixture", "tail", PriceCurrency.JPY, BigDecimal.ONE, false));
        restaurants.flush();
        List<String> reviewKeys = new ArrayList<>();
        for (int i = 0; i < 35; i++) {
            reviewKeys.add("review-" + i);
        }
        createReviewPages(reviewKeys);
        var first = service.getPhotos(restaurant.getId(), RestaurantPhotoFilter.ALL, null);
        assertThat(first.photos()).hasSize(20);
        for (int index = 0; index < 15; index++) {
            jdbc.update("delete from review_image where file_key = ?", "review-" + index);
        }
        jdbc.update("delete from restaurant_image where restaurant_id = ? and display_order <= 5", restaurant.getId());
        var newReview = createReview(List.of("new-photo"), false);
        jdbc.update("update review set created_at = '2026-02-01 00:00:00' where id = ?", newReview.getId());
        var second = service.getPhotos(restaurant.getId(), RestaurantPhotoFilter.ALL, first.nextCursor());
        assertThat(second.photos().subList(0, 4)).extracting(photo -> photo.imageUrl()).containsExactly(
                "https://images.test/hero-5", "https://images.test/review-15",
                "https://images.test/review-16", "https://images.test/review-17");
        var third = service.getPhotos(restaurant.getId(), RestaurantPhotoFilter.ALL, second.nextCursor());
        List<String> remaining = new ArrayList<>();
        second.photos().forEach(photo -> remaining.add(photo.imageUrl()));
        third.photos().forEach(photo -> remaining.add(photo.imageUrl()));
        assertThat(remaining).hasSize(26).doesNotHaveDuplicates().doesNotContain("https://images.test/new-photo");
        assertThat(remaining.getLast()).isEqualTo("https://images.test/tail");
        assertThat(third.hasNext()).isFalse();
    }

    @Test
    void 대표사진과_메뉴사진도_삭제된_경계ID_이후를_계속_조회한다() {
        List<RestaurantImage> representative = new ArrayList<>();
        for (int i = 0; i < 23; i++) {
            representative.add(RestaurantImage.create("hero-" + i, i + 1));
            restaurant.addMenu(RestaurantMenu.create("menu-" + i, "fixture", "menu-" + i,
                    PriceCurrency.JPY, BigDecimal.ONE, false));
        }
        restaurant.replaceImages(representative);
        restaurants.flush();
        for (var filter : List.of(RestaurantPhotoFilter.REPRESENTATIVE, RestaurantPhotoFilter.MENU)) {
            var first = service.getPhotos(restaurant.getId(), filter, null);
            long boundaryId = Long.parseLong(first.photos().getLast().photoId().split(":")[1]);
            String table = filter == RestaurantPhotoFilter.MENU ? "restaurant_menu" : "restaurant_image";
            jdbc.update("delete from " + table + " where id = ?", boundaryId);
            var second = service.getPhotos(restaurant.getId(), filter, first.nextCursor());
            assertThat(second.photos()).hasSize(3);
            assertThat(second.photos().getFirst().imageUrl()).endsWith("-20");
            assertThat(second.nextCursor()).isNull();
        }
    }

    @Test
    void 리뷰_키셋은_같은시각의_리뷰ID와_사진표시순서를_모두_비교한다() {
        Review first = createReview(List.of("first-0", "first-1"), false);
        Review second = createReview(List.of("second-0", "second-1"), false);
        jdbc.update("update review set created_at = '2026-01-01 00:00:00' where id in (?,?)",
                first.getId(), second.getId());
        var batch = photos.find(restaurant.getId(), false, List.of(), null, 1);
        assertThat(batch.getFirst().fileKey()).isEqualTo("second-0");
        assertThat(photos.find(restaurant.getId(), false, List.of(), batch.getFirst().position(), 20))
                .extracting(ReviewPhotoRepository.Photo::fileKey).containsExactly("second-1", "first-0", "first-1");
    }

    @Test
    void 생성시각이_없는_기존_리뷰는_마지막에_배치하고_커서로_이어간다() {
        Review missing = createReview(List.of("missing-0", "missing-1"), false);
        Review dated = createReview(List.of("dated"), false);
        jdbc.update("update review set created_at = null where id = ?", missing.getId());
        jdbc.update("update review set created_at = '2026-01-01 00:00:00' where id = ?", dated.getId());
        var first = photos.find(restaurant.getId(), false, List.of(), null, 1);
        var second = photos.find(restaurant.getId(), false, List.of(), first.getFirst().position(), 1);
        var third = photos.find(restaurant.getId(), false, List.of(), second.getFirst().position(), 20);
        assertThat(first.getFirst().fileKey()).isEqualTo("dated");
        assertThat(second.getFirst().fileKey()).isEqualTo("missing-0");
        assertThat(third).extracting(ReviewPhotoRepository.Photo::fileKey).containsExactly("missing-1");
    }

    @Test
    void 빈_사진은_200용_빈목록이고_삭제된_식당은_조회할수없다() {
        var result = service.getPhotos(restaurant.getId(), RestaurantPhotoFilter.ALL, null);
        assertThat(result.photos()).isEmpty();
        assertThat(result.counts().all()).isZero();
        assertThat(result.hasNext()).isFalse();
        jdbc.update("update restaurant set deleted=true where id=?", restaurant.getId());
        assertThatThrownBy(() -> service.getPhotos(restaurant.getId(), RestaurantPhotoFilter.ALL, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void key없는_asset도_전체탭_개수와_중복제거_커서에서_빠지지_않는다() {
        Review legacy = createReview(List.of("representative-key"), false);
        Review assetReview = Review.create(System.nanoTime(), restaurant.getId(), 1L, 5, "asset fixture");
        var first = java.util.UUID.randomUUID();
        var second = java.util.UUID.randomUUID();
        assetReview.replaceImages(List.of(ReviewImage.createAsset(first, 0), ReviewImage.createAsset(second, 1)));
        reviews.saveAndFlush(assetReview);
        assertThat(photos.count(restaurant.getId(), false, List.of())).isEqualTo(3);
        assertThat(photos.count(restaurant.getId(), true, List.of("representative-key"))).isEqualTo(2);
        var page1 = photos.find(restaurant.getId(), true, List.of("representative-key"), null, 1);
        var page2 = photos.find(restaurant.getId(), true, List.of("representative-key"), page1.getFirst().position(), 1);
        assertThat(page1.getFirst().imageAssetId()).isEqualTo(first);
        assertThat(page2.getFirst().imageAssetId()).isEqualTo(second);
        assertThat(page1.getFirst().id()).isNotEqualTo(page2.getFirst().id());
    }

    // 리뷰당 10장 제한을 지키면서 사진 탭의 여러 페이지를 구성한다.
    // 생성 시각이 같으면 큰 리뷰 ID가 먼저 오므로 뒷부분부터 저장한다.
    private void createReviewPages(List<String> keys) {
        for (int start = ((keys.size() - 1) / 10) * 10; start >= 0; start -= 10) {
            Review review = createReview(keys.subList(start, Math.min(start + 10, keys.size())), false);
            jdbc.update("update review set created_at = '2026-01-01 00:00:00' where id = ?", review.getId());
        }
    }

    private Review createReview(List<String> keys, boolean deleted) {
        Review review = Review.create(System.nanoTime(), restaurant.getId(), 1L, 5, "fixture");
        List<ReviewImage> images = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            images.add(ReviewImage.create(keys.get(i), i));
        }
        review.replaceImages(images);
        if (deleted) {
            review.softDelete();
        }
        return reviews.saveAndFlush(review);
    }
}
