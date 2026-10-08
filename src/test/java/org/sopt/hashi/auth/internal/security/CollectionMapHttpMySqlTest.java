package org.sopt.hashi.auth.internal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.auth.internal.jwt.AuthRoles;
import org.sopt.hashi.auth.internal.jwt.JwtProvider;
import org.sopt.hashi.auth.internal.jwt.MemberPrincipal;
import org.sopt.hashi.auth.internal.onboarding.OnboardingJwtIssuer;
import org.sopt.hashi.auth.internal.token.OnboardingTokenStore;
import org.sopt.hashi.auth.internal.token.TokenBlacklist;
import org.sopt.hashi.config.JpaAuditingConfig;
import org.sopt.hashi.config.TimeConfig;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.restaurant.RestaurantMapInfo;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.user.collection.domain.CollectionColor;
import org.sopt.hashi.user.collection.domain.CollectionVisibility;
import org.sopt.hashi.user.collection.domain.RestaurantCollection;
import org.sopt.hashi.user.collection.domain.RestaurantCollectionRepository;
import org.sopt.hashi.user.collection.domain.SavedRestaurantRepository;
import org.sopt.hashi.user.collection.dto.MoveSavedRestaurantsRequest;
import org.sopt.hashi.user.collection.dto.UpdateRestaurantCollectionRequest;
import org.sopt.hashi.user.collection.service.CollectionMapQueryService;
import org.sopt.hashi.user.collection.service.CollectionMapSnapshotStore;
import org.sopt.hashi.user.collection.service.RestaurantCollectionService;
import org.sopt.hashi.user.collection.web.CollectionMapController;
import org.sopt.hashi.user.domain.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpHeaders;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 실제 HTTP/OSIV/컬렉션 JPA만 로딩한다. 다른 도메인 구현은 Port로 격리한다. */
@Testcontainers(disabledWithoutDocker = true)
@WebMvcTest(controllers = CollectionMapController.class,
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = OnboardingJwtIssuer.class))
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtProvider.class, CookieUtil.class, OriginValidator.class,
        JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class, CurrentUserProviderImpl.class,
        TimeConfig.class, JpaAuditingConfig.class,
        CollectionMapHttpMySqlTest.Persistence.class})
@ImportAutoConfiguration({DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
        TransactionAutoConfiguration.class, FlywayAutoConfiguration.class})
@TestPropertySource(properties = {
        "spring.jpa.open-in-view=true", "spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
        "jwt.secret=test-secret-key-must-be-at-least-32-bytes-long", "jwt.access-token-ttl=30m",
        "jwt.refresh-token-ttl=14d", "jwt.onboarding-token-ttl=30m", "kakao.client-id=test-client-id",
        "kakao.redirect-uri=https://app.hashi.com/callback", "hashi.cors.allowed-origins=https://app.hashi.com",
        "springdoc.api-docs.enabled=false", "springdoc.swagger-ui.enabled=false"
})
class CollectionMapHttpMySqlTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("hashi").withUsername("hashi").withPassword("hashi");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @TestConfiguration
    @EntityScan(basePackageClasses = RestaurantCollection.class)
    @EnableJpaRepositories(basePackageClasses = RestaurantCollectionRepository.class)
    @ComponentScan(basePackageClasses = RestaurantCollectionService.class)
    static class Persistence { }

    @Autowired MockMvc mvc;
    @Autowired JwtProvider jwt;
    @Autowired RestaurantCollectionRepository collections;
    @Autowired SavedRestaurantRepository saved;
    @Autowired RestaurantCollectionService writes;
    @Autowired EntityManagerFactory entityManagerFactory;
    @MockitoBean OnboardingTokenStore onboardingTokenStore;
    @MockitoBean TokenBlacklist tokenBlacklist;
    @MockitoBean RestaurantPort restaurants;
    @MockitoBean MediaPort media;
    @MockitoBean UserRepository userRepository;
    @MockitoBean(name = "japanClock") Clock clock;

    @BeforeEach
    void setUp() {
        given(clock.instant()).willReturn(Instant.parse("2026-10-01T00:00:00Z"));
        given(restaurants.findActiveMapInfos(anyCollection())).willAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return ids.stream().map(this::info).toList();
        });
    }

    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
        saved.deleteAllInBatch();
        collections.deleteAllInBatch();
    }

    @Test
    void 공개_익명_전체핀과_비공개_소유USER만_허용하며_쓰기는_계속_보호한다() throws Exception {
        Long publicId = seed("공개", CollectionVisibility.PUBLIC, 1L, 2L);
        Long privateId = seed("비공개", CollectionVisibility.PRIVATE, 1L);
        mvc.perform(get(path(publicId))).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.data.visibleRestaurantCount").value(2))
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.generatedAt").value("2026-10-01T00:00:00Z"));
        mvc.perform(get(path(privateId))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("USER-006"));
        mvc.perform(get(path(privateId)).header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + jwt.createAccessToken(1L, AuthRoles.ADMIN)))
                .andExpect(status().isNotFound());
        mvc.perform(get(path(privateId)).header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + jwt.createAccessToken(1L, AuthRoles.USER)))
                .andExpect(status().isOk());
        mvc.perform(get(path(0L))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-400"));
        mvc.perform(get(path(Long.MAX_VALUE))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/collections/" + publicId + "/restaurants"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void OSIV_HTTP_조회중_비공개_전환은_stale_Entity가_있어도_404다() throws Exception {
        Long id = seed("OSIV", CollectionVisibility.PUBLIC, 1L);
        AtomicBoolean staleEntity = new AtomicBoolean();
        var result = race(id, false, () -> writes.update(id, new UpdateRestaurantCollectionRequest(null, null, null, "private")),
                staleEntity);
        status().isNotFound().match(result);
        jsonPath("$.code").value("USER-006").match(result);
        assertThat(staleEntity).isTrue();
    }

    @Test
    void HTTP_조회중_삭제는_404이고_이동은_409다() throws Exception {
        Long deleted = seed("삭제", CollectionVisibility.PUBLIC, 1L);
        status().isNotFound().match(race(deleted, false, () -> writes.delete(deleted), new AtomicBoolean()));
        Long source = seed("원본", CollectionVisibility.PUBLIC, 1L);
        Long target = seed("대상", CollectionVisibility.PUBLIC);
        var moved = race(source, true,
                () -> writes.moveRestaurants(source, new MoveSavedRestaurantsRequest(target, List.of(1L))), new AtomicBoolean());
        status().isConflict().match(moved);
        jsonPath("$.code").value("COMMON-409").match(moved);
    }

    private org.springframework.test.web.servlet.MvcResult race(Long id, boolean owner, Runnable mutation,
                                                                 AtomicBoolean staleEntity) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.hasResource(entityManagerFactory)).isTrue();
            entered.countDown();
            assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
            var holder = (EntityManagerHolder) TransactionSynchronizationManager.getResource(entityManagerFactory);
            staleEntity.set(holder.getEntityManager().find(RestaurantCollection.class, id).isPublic());
            return List.of(info(1L));
        }).when(restaurants).findActiveMapInfos(anyCollection());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var request = get(path(id));
            if (owner) {
                request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.createAccessToken(1L, AuthRoles.USER));
            }
            var future = executor.submit(() -> mvc.perform(request).andReturn());
            try {
                assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                        new MemberPrincipal(1L), null, List.of(new SimpleGrantedAuthority(AuthRoles.USER))));
                mutation.run();
            } finally {
                SecurityContextHolder.clearContext();
                release.countDown();
            }
            return future.get(30, TimeUnit.SECONDS);
        }
    }

    private Long seed(String name, CollectionVisibility visibility, Long... ids) {
        var collection = RestaurantCollection.create(1L, name, CollectionColor.RED, null, visibility);
        for (Long id : ids) {
            collection.save(id);
        }
        return collections.saveAndFlush(collection).getId();
    }

    private String path(Long id) {
        return "/api/v1/collections/" + id + "/map-markers";
    }

    private RestaurantMapInfo info(Long id) {
        return new RestaurantMapInfo(id, "식당", "restaurant", "japanese",
                new RestaurantMapInfo.LocationInfo(new BigDecimal("35.6"), new BigDecimal("139.7"),
                        Instant.parse("2026-10-02T00:00:00Z")));
    }
}
