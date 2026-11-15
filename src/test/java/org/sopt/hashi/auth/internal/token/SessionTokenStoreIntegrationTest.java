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
        var first = prepareCode();
        store.exchange(first, "code", VERIFIER, "previous-child", "previous-refresh");
        store.issueCode(7L, "app", "code", SessionTokenStore.digest(VERIFIER));
        var bridge = store.findCode("code");
        var results = race(() -> store.exchange(bridge, "code", VERIFIER, "child1", "r1"),
                () -> store.exchange(bridge, "code", VERIFIER, "child2", "r2"));
        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertThat(redis.opsForHash().size(KEY)).isEqualTo(2);
        assertThat(redis.opsForHash().hasKey(KEY, "previous-child")).isFalse();
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

    @Test
    void 반복_교환은_같은_부모의_이전_WebView만_교체한다() {
        store.save(7L, "app", "app", "refresh");
        store.save(7L, "other-app", "app", "other-refresh");
        store.save(7L, "browser", "web", "browser-refresh");
        store.issueCode(7L, "other-app", "other-code", SessionTokenStore.digest(VERIFIER));
        store.exchange(store.findCode("other-code"), "other-code", VERIFIER, "other-child", "other-child-refresh");
        for (int i = 0; i < 140; i++) {
            String code = "code-" + i;
            store.issueCode(7L, "app", code, SessionTokenStore.digest(VERIFIER));
            store.exchange(store.findCode(code), code, VERIFIER, "child-" + i, "refresh-" + i);
            assertThat(redis.opsForHash().keys(KEY))
                    .containsExactlyInAnyOrder("app", "other-app", "browser", "other-child", "child-" + i);
        }
        store.rotate(7L, "other-child", "web", "other-child-refresh", "other-child-next");
        store.rotate(7L, "browser", "web", "browser-refresh", "browser-next");
        assertThatThrownBy(() -> store.rotate(7L, "child-138", "web", "refresh-138", "unused"))
                .isInstanceOf(BusinessException.class);
        store.rotate(7L, "child-139", "web", "refresh-139", "latest");
    }

    @Test
    void 세션_상한에서도_기존_WebView_교체는_성공한다() {
        var bridge = prepareCode();
        store.exchange(bridge, "code", VERIFIER, "previous-child", "previous-refresh");
        for (int i = 0; i < 126; i++) store.save(7L, "browser-" + i, "web", "token-" + i);
        store.issueCode(7L, "app", "new-code", SessionTokenStore.digest(VERIFIER));
        store.exchange(store.findCode("new-code"), "new-code", VERIFIER, "new-child", "new-refresh");
        assertThat(redis.opsForHash().size(KEY)).isEqualTo(128);
        assertThat(redis.opsForHash().hasKey(KEY, "previous-child")).isFalse();
        store.rotate(7L, "new-child", "web", "new-refresh", "rotated");
        store.rotate(7L, "browser-0", "web", "token-0", "browser-rotated");
    }

    @Test
    void 잘못된_교환과_재사용은_현재_WebView를_보존한다() {
        var bridge = prepareCode();
        store.exchange(bridge, "code", VERIFIER, "previous-child", "previous-refresh");
        store.issueCode(7L, "app", "new-code", SessionTokenStore.digest(VERIFIER));
        var next = store.findCode("new-code");
        assertThatThrownBy(() -> store.exchange(next, "new-code", "wrong", "rejected", "unused"))
                .isInstanceOf(BusinessException.class);
        assertThat(redis.opsForHash().keys(KEY)).containsExactlyInAnyOrder("app", "previous-child");
        store.rotate(7L, "previous-child", "web", "previous-refresh", "rotated");
        store.exchange(next, "new-code", VERIFIER, "new-child", "new-refresh");
        assertThatThrownBy(() -> store.exchange(next, "new-code", VERIFIER, "replay", "unused"))
                .isInstanceOf(BusinessException.class);
        assertThat(redis.opsForHash().keys(KEY)).containsExactlyInAnyOrder("app", "new-child");
        store.rotate(7L, "new-child", "web", "new-refresh", "latest");
    }

    @Test
    void 상한에서_기존_자식이_없는_교환은_다른_세션과_코드를_보존한다() {
        var bridge = prepareCode();
        for (int i = 0; i < 127; i++) store.save(7L, "browser-" + i, "web", "token-" + i);
        assertThatThrownBy(() -> store.exchange(bridge, "code", VERIFIER, "child", "refresh"))
                .isInstanceOf(BusinessException.class);
        assertThat(redis.opsForHash().size(KEY)).isEqualTo(128);
        assertThat(store.findCode("code")).isEqualTo(bridge);
        store.rotate(7L, "browser-0", "web", "token-0", "browser-rotated");
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
