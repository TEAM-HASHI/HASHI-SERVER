package org.sopt.hashi.auth.internal.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.auth.internal.jwt.JwtProperties;
import org.sopt.hashi.config.RedisConfig;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class SessionTokenStoreIntegrationTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withLabel("hashi.task", "rn-auth-284").withExposedPorts(6379);
    static LettuceConnectionFactory connection;
    static StringRedisTemplate redis;
    static RedisTemplate<String, Object> legacy;
    static SessionTokenStore store;
    static final String KEY = "auth:sessions:ROLE_USER:7";
    static final String VERIFIER = "v".repeat(43);

    @BeforeAll
    static void connect() {
        connection = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection);
        legacy = new RedisConfig().redisTemplate(connection);
        store = new SessionTokenStore(redis, legacy, new JwtProperties("test-secret-long-enough-for-32-bytes",
                Duration.ofMinutes(30), Duration.ofDays(14), Duration.ofMinutes(30)), new ObjectMapper());
    }

    @AfterAll
    static void disconnect() { if (connection != null) connection.destroy(); }

    @BeforeEach
    void clearOwnedContainer() {
        try (var conn = connection.getConnection()) { conn.serverCommands().flushDb(); }
    }

    @Test
    void 앱_로그아웃은_자식만_지우고_별도_웹과_다른_앱은_남긴다() {
        store.save(7L, "app1", "app", "app-refresh");
        store.save(7L, "app2", "app", "other-app");
        store.save(7L, "browser", "web", "web-refresh");
        store.issueCode(7L, "app1", "code", SessionTokenStore.digest(VERIFIER));
        store.exchange(store.findCode("code"), "code", VERIFIER, "child", "child-refresh");
        store.revoke(7L, "app1", "app", "app-refresh");
        assertThat(redis.opsForHash().keys(KEY)).containsExactlyInAnyOrder("app2", "browser");
    }

    @Test
    void 코드_동시_교환은_한_요청만_성공한다() throws Exception {
        var bridge = prepareCode();
        var results = race(() -> store.exchange(bridge, "code", VERIFIER, "child1", "r1"),
                () -> store.exchange(bridge, "code", VERIFIER, "child2", "r2"));
        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertThat(redis.opsForHash().size(KEY)).isEqualTo(2);
        assertThatThrownBy(() -> store.findCode("code")).isInstanceOf(BusinessException.class);
    }

    @Test
    void 앱_로그아웃과_교환이_겹쳐도_자식_세션이_남지_않는다() throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            var bridge = prepareCode();
            race(() -> store.exchange(bridge, "code", VERIFIER, "child", "r1"),
                    () -> store.revoke(7L, "app", "app", "refresh"));
            assertThat(redis.opsForHash().size(KEY)).isZero();
        }
    }

    @Test
    void 기존_직렬화된_토큰_동시_전환은_한_요청만_성공한다() throws Exception {
        legacy.opsForValue().set("auth:refresh:ROLE_USER:7", "legacy-token", Duration.ofDays(1));
        var results = race(() -> store.migrate(7L, "web1", "legacy-token", "new1"),
                () -> store.migrate(7L, "web2", "legacy-token", "new2"));
        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertThat(redis.hasKey("auth:refresh:ROLE_USER:7")).isFalse();
        assertThat(redis.opsForHash().size(KEY)).isEqualTo(1);
    }

    @Test
    void 재사용_감지는_해당_앱과_자식만_폐기한다() {
        var bridge = prepareCode();
        store.exchange(bridge, "code", VERIFIER, "child", "r1");
        store.save(7L, "browser", "web", "web");
        store.rotate(7L, "app", "app", "refresh", "rotated");
        assertThatThrownBy(() -> store.rotate(7L, "app", "app", "refresh", "stolen"))
                .isInstanceOf(BusinessException.class);
        assertThat(redis.opsForHash().keys(KEY)).containsExactly("browser");
    }

    @Test
    void 잘못된_verifier는_소비하지_않고_만료된_코드는_거절한다() {
        var bridge = prepareCode();
        assertThatThrownBy(() -> store.exchange(bridge, "code", "wrong", "child", "r1"))
                .isInstanceOf(BusinessException.class);
        assertThat(store.findCode("code")).isEqualTo(bridge);
        String codeKey = "auth:webview:code:" + SessionTokenStore.digest("code");
        assertThat(redis.getExpire(codeKey)).isBetween(1L, 60L);
        redis.expire(codeKey, Duration.ZERO);
        assertThatThrownBy(() -> store.exchange(bridge, "code", VERIFIER, "child", "r1"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 탈퇴_차단_후에는_세션_생성과_교환을_거절한다() {
        var bridge = prepareCode();
        legacy.opsForValue().set("auth:blacklist:user:7", "withdrawn", Duration.ofDays(14));
        assertThatThrownBy(() -> store.save(7L, "new", "app", "token")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> store.exchange(bridge, "code", VERIFIER, "child", "r1"))
                .isInstanceOf(BusinessException.class);
        store.revokeAll(7L);
        assertThat(redis.opsForHash().size(KEY)).isZero();
    }

    @Test
    void 다른_종류의_토큰으로_회전하지_못하고_상한을_넘으면_기존_세션은_보존한다() {
        store.save(7L, "app", "app", "refresh");
        assertThatThrownBy(() -> store.rotate(7L, "app", "web", "refresh", "r1"))
                .isInstanceOf(BusinessException.class);
        for (int i = 0; i < 127; i++) store.save(7L, "web" + i, "web", "token" + i);
        assertThatThrownBy(() -> store.save(7L, "overflow", "app", "r")).isInstanceOf(BusinessException.class);
        store.rotate(7L, "app", "app", "refresh", "r2");
        assertThat(redis.opsForHash().size(KEY)).isEqualTo(128);
    }

    private SessionTokenStore.BridgeCode prepareCode() {
        store.save(7L, "app", "app", "refresh");
        store.issueCode(7L, "app", "code", SessionTokenStore.digest(VERIFIER));
        return store.findCode("code");
    }

    private List<Boolean> race(Runnable first, Runnable second) throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> a = () -> invoke(gate, first);
            Callable<Boolean> b = () -> invoke(gate, second);
            var fa = executor.submit(a);
            var fb = executor.submit(b);
            gate.countDown();
            return List.of(fa.get(), fb.get());
        }
    }

    private boolean invoke(CountDownLatch gate, Runnable task) throws InterruptedException {
        gate.await();
        try { task.run(); return true; } catch (BusinessException e) { return false; }
    }
}
