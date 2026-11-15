package org.sopt.hashi.restaurant.service;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sopt.hashi.config.TimeConfig;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.restaurant.domain.*;
import org.sopt.hashi.restaurant.dto.RestaurantListResponse;
import org.sopt.hashi.restaurant.web.RestaurantController;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.exception.GlobalExceptionHandler;
import org.sopt.hashi.shared.storage.FileStorage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({RestaurantService.class, TimeConfig.class})
@TestPropertySource(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
class RestaurantSearchIntegrationTest {
    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    @Autowired RestaurantService service;
    @Autowired RestaurantRepository repository;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean FileStorage fileStorage;
    @MockitoBean MediaPort mediaPort;
    @MockitoBean RestaurantLocationService locationService;

    @Test
    void OR검색은_단어수와_식당명_메뉴명_태그_순서로_중복없이_정렬한다() {
        Restaurant both = restaurant("스시 라멘", List.of(), List.of());
        Restaurant name = restaurant("스시 전문", List.of(), List.of());
        Restaurant menu = restaurant("메뉴 식당", List.of("스시", "스시 세트", "스시 정식"), List.of());
        Restaurant tag = restaurant("태그 식당", List.of(), List.of("가성비스시", "스시맛집"));
        restaurant("무관", List.of(), List.of());
        flush();
        assertThat(ids(search("스시 라멘", "basic", null, 10)))
                .containsExactly(both.getId(), name.getId(), menu.getId(), tag.getId());
        assertThat(ids(search("스시 스시 라멘", "basic", null, 10)))
                .containsExactly(both.getId(), name.getId(), menu.getId(), tag.getId());
    }

    @Test
    void 태그_전용검색과_일반검색은_대소문자_구분없이_부분일치한다() {
        Restaurant tag = restaurant("태그", List.of(), List.of("BestSushi"));
        Restaurant name = restaurant("SUSHI", List.of(), List.of());
        flush();
        assertThat(ids(search("#sushi", "basic", null, 10))).containsExactly(tag.getId());
        assertThat(ids(search("sushi", "basic", null, 10))).containsExactly(name.getId(), tag.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"%", "_", "!"})
    void LIKE_특수문자는_리터럴로_검색한다(String literal) {
        Restaurant hit = restaurant("스시" + literal, List.of(), List.of());
        restaurant("스시 일반", List.of(), List.of());
        flush();
        assertThat(ids(search(literal, "basic", null, 10))).containsExactly(hit.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"basic", "popular", "rating"})
    void 같은_점수의_여러_페이지에서_중복과_누락없이_내림차순_조회한다(String sort) {
        List<Long> expected = new ArrayList<>();
        for (int i = 0; i < 23; i++) expected.addFirst(restaurant("검색 " + i, List.of(), List.of()).getId());
        flush();
        List<Long> actual = new ArrayList<>();
        String cursor = null;
        do {
            var page = search("검색", sort, cursor, 10);
            actual.addAll(ids(page));
            cursor = page.nextCursor();
        } while (cursor != null);
        assertThat(actual).containsExactlyElementsOf(expected);
    }

    @Test
    void 별점순은_동일별점에서_리뷰수가_많은_식당을_우선한다() {
        Restaurant many = restaurant("검색 A", List.of(), List.of());
        Restaurant few = restaurant("검색 B", List.of(), List.of());
        em.flush();
        jdbc.update("update restaurant set rating=4.5,review_count=10 where id=?", many.getId());
        jdbc.update("update restaurant set rating=4.5,review_count=2 where id=?", few.getId());
        em.clear();
        var first = search("검색", "rating", null, 1);
        assertThat(ids(first)).containsExactly(many.getId());
        assertThat(ids(search("검색", "rating", first.nextCursor(), 1))).containsExactly(few.getId());
    }

    @Test
    void 커서_식당이_삭제되어도_다음_페이지를_조회하고_조건변경은_거부한다() {
        Restaurant a = restaurant("검색 A", List.of(), List.of());
        Restaurant b = restaurant("검색 B", List.of(), List.of());
        flush();
        var first = search("검색", "basic", null, 1);
        jdbc.update("update restaurant set deleted=true where id=?", b.getId());
        em.clear();
        assertThat(ids(search("검색", "basic", first.nextCursor(), 2))).containsExactly(a.getId());
        assertThatThrownBy(() -> search("다른", "basic", first.nextCursor(), 1)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> search("검색", "rating", first.nextCursor(), 1)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.getRestaurants("검색", "sushi", "basic", null, first.nextCursor(), 1))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.getRestaurants("검색", null, "basic", "sns-hot", first.nextCursor(), 1))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void HTTP는_실제_서비스의_태그검색_빈결과와_잘못된검색어를_구분한다() throws Exception {
        Restaurant tag = restaurant("태그 식당", List.of(), List.of("가성비"));
        flush();
        var mvc = MockMvcBuilders.standaloneSetup(new RestaurantController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/api/v1/restaurants").param("keyword", "#가성비"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].restaurantId").value(tag.getId()));
        mvc.perform(get("/api/v1/restaurants").param("keyword", "없음"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.hasNext").value(false));
        mvc.perform(get("/api/v1/restaurants").param("keyword", "#가성비 스시"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 정확도_단어수와_출처가_다른_경계도_커서로_이어진다() {
        Restaurant cross = restaurant("스시", List.of("라멘"), List.of());
        Restaurant name = restaurant("스시 전문", List.of(), List.of());
        Restaurant menu = restaurant("메뉴", List.of("스시"), List.of());
        Restaurant tag = restaurant("태그", List.of(), List.of("스시"));
        flush();
        List<Long> actual = new ArrayList<>();
        String cursor = null;
        do {
            var page = search("스시 라멘", "basic", cursor, 1);
            actual.addAll(ids(page));
            cursor = page.nextCursor();
        } while (cursor != null);
        assertThat(actual).containsExactly(cross.getId(), name.getId(), menu.getId(), tag.getId());
    }

    @Test
    void 인기순은_리뷰수_별점_ID_경계를_순서대로_이어간다() {
        Restaurant manyLow = restaurant("검색 A", List.of(), List.of());
        Restaurant manyHigh = restaurant("검색 B", List.of(), List.of());
        Restaurant few = restaurant("검색 C", List.of(), List.of());
        em.flush();
        jdbc.update("update restaurant set rating=3.0,review_count=10 where id=?", manyLow.getId());
        jdbc.update("update restaurant set rating=4.0,review_count=10 where id=?", manyHigh.getId());
        jdbc.update("update restaurant set rating=5.0,review_count=2 where id=?", few.getId());
        em.clear();
        var first = search("검색", "popular", null, 1);
        var second = search("검색", "popular", first.nextCursor(), 1);
        var third = search("검색", "popular", second.nextCursor(), 1);
        assertThat(ids(first)).containsExactly(manyHigh.getId());
        assertThat(ids(second)).containsExactly(manyLow.getId());
        assertThat(ids(third)).containsExactly(few.getId());
        assertThat(third.hasNext()).isFalse();
    }

    @Test
    void 정규화한_동일조건_커서는_재사용하고_해시태그모드변경은_거부한다() {
        Restaurant a = restaurant("Sushi", List.of(), List.of());
        restaurant("Sushi second", List.of(), List.of());
        flush();
        var first = search("sushi", "basic", null, 1);
        assertThat(ids(search(" SUSHI sushi ", "basic", first.nextCursor(), 2))).containsExactly(a.getId());
        assertThatThrownBy(() -> search("#sushi", "basic", first.nextCursor(), 1))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 장르와_큐레이션_조건은_태그검색에도_함께_적용한다() {
        Restaurant included = restaurant("대상", List.of(), List.of("가성비"));
        included.replaceCurationTypes(List.of(RestaurantCurationType.SNS_HOT));
        restaurant("다른 큐레이션", List.of(), List.of("가성비"));
        flush();
        assertThat(ids(service.getRestaurants("#가성비", "sushi", "basic", "sns-hot", null, 10)))
                .containsExactly(included.getId());
        assertThat(service.getRestaurants("#가성비", "noodle", "basic", null, null, 10).content()).isEmpty();
    }

    private Restaurant restaurant(String name, List<String> menus, List<String> tags) {
        Restaurant restaurant = Restaurant.create(name, name, "summary", "description", "address", "area",
                RestaurantGenre.SUSHI, "food", RestaurantPlaceType.RESTAURANT,
                PriceCurrency.JPY, BigDecimal.ONE, BigDecimal.TEN);
        restaurant.replaceHashtags(tags);
        menus.forEach(menu -> restaurant.addMenu(RestaurantMenu.create(menu, "menu", null, PriceCurrency.JPY,
                BigDecimal.ONE, false)));
        return repository.saveAndFlush(restaurant);
    }
    private void flush() { em.flush(); em.clear(); }
    private RestaurantListResponse search(String keyword, String sort, String cursor, int size) {
        return service.getRestaurants(keyword, null, sort, null, cursor, size);
    }
    private List<Long> ids(RestaurantListResponse page) {
        return page.content().stream().map(RestaurantListResponse.RestaurantSummaryResponse::restaurantId).toList();
    }
}
