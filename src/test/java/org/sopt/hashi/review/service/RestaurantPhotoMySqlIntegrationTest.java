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
        for (int i = 0; i < 43; i++) reviewKeys.add("review-" + i);
        reviewKeys.add("review-0");
        createReview(reviewKeys, false);
        createReview(List.of("deleted-photo"), true);
        var first = service.getPhotos(restaurant.getId(), RestaurantPhotoFilter.ALL, 0);
        assertThat(first.counts().representative()).isEqualTo(2);
        assertThat(first.counts().menu()).isEqualTo(2);
        assertThat(first.counts().review()).isEqualTo(45);
        assertThat(first.counts().all()).isEqualTo(46);
        assertThat(first.photos()).hasSize(20);
        assertThat(first.photos().subList(0, 8)).extracting(photo -> photo.imageUrl()).containsExactly(
                "https://images.test/same", "https://images.test/review-0", "https://images.test/review-1",
                "https://images.test/review-2", "https://images.test/hero2", "https://images.test/review-3",
                "https://images.test/review-4", "https://images.test/review-5");
        var second = service.getPhotos(restaurant.getId(), RestaurantPhotoFilter.ALL, first.nextPage());
        var third = service.getPhotos(restaurant.getId(), RestaurantPhotoFilter.ALL, second.nextPage());
        List<String> urls = new ArrayList<>();
        for (var page : List.of(first, second, third)) page.photos().forEach(photo -> urls.add(photo.imageUrl()));
        assertThat(urls).hasSize(46).doesNotHaveDuplicates();
        assertThat(urls.getLast()).isEqualTo("https://images.test/menu-only");
        assertThat(third.hasNext()).isFalse();
        assertThat(third.nextPage()).isNull();
        assertThat(service.getPhotos(restaurant.getId(), RestaurantPhotoFilter.ALL, Integer.MAX_VALUE).photos()).isEmpty();
        assertThat(service.getPhotos(restaurant.getId(), RestaurantPhotoFilter.REVIEW, 0).totalCount()).isEqualTo(45);
    }

    @Test
    void 최신_리뷰와_동일시각_ID_순서_및_대소문자가_다른_원본을_보존한다() {
        Review old = createReview(List.of("A", "B"), false);
        Review newer = createReview(List.of("a", "newer"), false);
        jdbc.update("update review set created_at = '2026-01-01 00:00:00' where id in (?,?)", old.getId(), newer.getId());
        assertThat(photos.find(restaurant.getId(), true, List.of(), 0, 20))
                .extracting(ReviewPhotoRepository.Photo::fileKey).containsExactly("a", "newer", "A", "B");
        assertThat(photos.count(restaurant.getId(), true, List.of("a"))).isEqualTo(3);
        assertThat(photos.findExistingKeys(restaurant.getId(), List.of("A", "missing"))).containsExactly("A");
        newer.softDelete();
        reviews.flush();
        assertThat(service.getPhotos(restaurant.getId(), RestaurantPhotoFilter.REVIEW, 0).totalCount()).isEqualTo(2);
    }

    @Test
    void 빈_사진은_200용_빈목록이고_삭제된_식당은_조회할수없다() {
        var result = service.getPhotos(restaurant.getId(), RestaurantPhotoFilter.ALL, 0);
        assertThat(result.photos()).isEmpty();
        assertThat(result.counts().all()).isZero();
        assertThat(result.hasNext()).isFalse();
        jdbc.update("update restaurant set deleted=true where id=?", restaurant.getId());
        assertThatThrownBy(() -> service.getPhotos(restaurant.getId(), RestaurantPhotoFilter.ALL, 0))
                .isInstanceOf(BusinessException.class);
    }

    private Review createReview(List<String> keys, boolean deleted) {
        Review review = Review.create(System.nanoTime(), restaurant.getId(), 1L, 5, "fixture");
        List<ReviewImage> images = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) images.add(ReviewImage.create(keys.get(i), i));
        review.replaceImages(images);
        if (deleted) review.softDelete();
        return reviews.saveAndFlush(review);
    }
}
