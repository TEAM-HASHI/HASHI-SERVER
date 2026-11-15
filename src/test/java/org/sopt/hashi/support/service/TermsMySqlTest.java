package org.sopt.hashi.support.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.config.TimeConfig;
import org.sopt.hashi.support.TermsClause;
import org.sopt.hashi.support.TermsCommand;
import org.sopt.hashi.support.TermsType;
import org.sopt.hashi.support.domain.TermsVersionRepository;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TermsService.class, TermsContentCodec.class, ObjectMapper.class, TimeConfig.class})
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TermsMySqlTest {
    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withCommand("--log-bin-trust-function-creators=1").withDatabaseName("hashi").withUsername("hashi").withPassword("hashi");
    @Autowired TermsService service;
    @Autowired TermsVersionRepository repository;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void 정리() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_terms_publication");
        jdbc.update("UPDATE support_terms_type SET current_version_id = NULL");
        repository.deleteAll();
    }

    private TermsCommand command(TermsType type, String version) {
        return new TermsCommand(type, type.title(), version, LocalDate.of(2026, 10, 1),
                List.of(new TermsClause("제1조", "시험용 약관 본문")));
    }

    @Test
    void 미게시_8유형을_관리목록에서_보이고_공개목록은_현재버전만_고정순서로_보인다() {
        assertThat(service.adminTypes()).hasSize(8).allSatisfy(t -> assertThat(t.currentTermsId()).isNull());
        assertThat(service.currentList()).isEmpty();
        TermsType[] types = TermsType.values();
        for (int i = types.length - 1; i >= 0; i--) {
            var draft = service.create(command(types[i], "1"));
            assertThatThrownBy(() -> service.currentDetail(draft.termsId())).isInstanceOf(BusinessException.class);
            service.publish(draft.termsId());
        }
        assertThat(service.currentList()).extracting(v -> v.type()).containsExactly(types);
    }

    @Test
    void 관리자_이력은_선택한_유형의_페이지와_전체건수를_반환한다() {
        List<Long> ids = java.util.stream.IntStream.range(0, 23).mapToObj(i ->
                service.create(command(TermsType.SERVICE_TERMS, "v" + i)).termsId()).toList();
        service.create(command(TermsType.PRIVACY_POLICY, "v0"));
        service.publish(ids.get(1));
        service.publish(ids.get(2));
        service.delete(ids.getFirst());
        var first = service.adminHistory(TermsType.SERVICE_TERMS, 0, 20);
        var last = service.adminHistory(TermsType.SERVICE_TERMS, 1, 20);
        assertThat(first.getTotalElements()).isEqualTo(22);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.getContent()).hasSize(20);
        assertThat(last.getContent()).extracting(org.sopt.hashi.support.TermsInfo::termsId)
                .containsExactly(ids.get(2), ids.get(1));
        assertThat(last.getContent()).extracting(org.sopt.hashi.support.TermsInfo::status)
                .containsExactly("CURRENT", "ARCHIVED");
        assertThat(service.adminHistory(TermsType.SERVICE_TERMS, 2, 20).getContent()).isEmpty();
        assertThat(service.adminHistory(TermsType.SERVICE_TERMS, -1, 0).getSize()).isEqualTo(20);
        assertThat(service.adminHistory(TermsType.SERVICE_TERMS, 0, 1000).getSize()).isEqualTo(100);
    }

    @Test
    void 현재_상세는_요청한_ID만_반환하고_초안과_보관버전은_숨긴다() {
        var old = service.create(command(TermsType.SERVICE_TERMS, "1"));
        service.publish(old.termsId());
        var current = service.create(command(TermsType.SERVICE_TERMS, "2"));
        service.publish(current.termsId());
        var other = service.create(command(TermsType.PRIVACY_POLICY, "1"));
        service.publish(other.termsId());
        var draft = service.create(command(TermsType.SERVICE_TERMS, "3"));
        assertThat(service.currentDetail(current.termsId()).termsId()).isEqualTo(current.termsId());
        assertThat(service.currentDetail(other.termsId()).type()).isEqualTo(TermsType.PRIVACY_POLICY);
        for (Long hidden : List.of(old.termsId(), draft.termsId(), Long.MAX_VALUE)) {
            assertThatThrownBy(() -> service.currentDetail(hidden)).isInstanceOf(BusinessException.class);
        }
    }

    @Test
    void 초안은_수정삭제하고_게시와_보관버전은_불변이며_중복을_거절한다() {
        var first = service.create(command(TermsType.SERVICE_TERMS, "1"));
        assertThatThrownBy(() -> service.create(command(TermsType.SERVICE_TERMS, "1")))
                .isInstanceOf(BusinessException.class);
        service.update(first.termsId(), command(TermsType.SERVICE_TERMS, "1.0"));
        service.publish(first.termsId());
        var second = service.create(command(TermsType.SERVICE_TERMS, "2"));
        service.publish(second.termsId());
        assertThat(service.adminDetail(first.termsId()).status()).isEqualTo("ARCHIVED");
        assertThat(service.adminDetail(second.termsId()).status()).isEqualTo("CURRENT");
        assertThatThrownBy(() -> service.currentDetail(first.termsId())).isInstanceOf(BusinessException.class);
        for (Long id : List.of(first.termsId(), second.termsId())) {
            assertThatThrownBy(() -> service.update(id, command(TermsType.SERVICE_TERMS, "3")))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> service.delete(id)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> service.publish(id)).isInstanceOf(BusinessException.class);
        }
        var disposable = service.create(command(TermsType.PRIVACY_POLICY, "1"));
        assertThatThrownBy(() -> service.update(disposable.termsId(), command(TermsType.SERVICE_TERMS, "4")))
                .isInstanceOf(BusinessException.class);
        service.delete(disposable.termsId());
        assertThatThrownBy(() -> service.adminDetail(disposable.termsId())).isInstanceOf(BusinessException.class);
    }

    @Test
    void 현재pointer_저장이_실패하면_새게시도_롤백하고_기존현재를_유지한다() {
        var first = service.create(command(TermsType.PRIVACY_POLICY, "1"));
        service.publish(first.termsId());
        var second = service.create(command(TermsType.PRIVACY_POLICY, "2"));
        jdbc.execute("""
                CREATE TRIGGER fail_terms_publication BEFORE UPDATE ON support_terms_type
                FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'synthetic publication failure'
                """);
        try {
            assertThatThrownBy(() -> service.publish(second.termsId())).isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("DROP TRIGGER fail_terms_publication");
        }
        assertThat(service.currentDetail(first.termsId()).status()).isEqualTo("CURRENT");
        assertThat(service.adminDetail(second.termsId()).status()).isEqualTo("DRAFT");
        assertThat(service.adminDetail(second.termsId()).publishedAt()).isNull();
    }

    @Test
    void 같은유형의_동시게시는_현재하나와_보관하나를_남긴다() throws Exception {
        var a = service.create(command(TermsType.SERVICE_TERMS, "1"));
        var b = service.create(command(TermsType.SERVICE_TERMS, "2"));
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var fa = executor.submit(() -> { start.await(); return service.publish(a.termsId()); });
            var fb = executor.submit(() -> { start.await(); return service.publish(b.termsId()); });
            start.countDown();
            assertThat(fa.get(15, TimeUnit.SECONDS).publishedAt()).isNotNull();
            assertThat(fb.get(15, TimeUnit.SECONDS).publishedAt()).isNotNull();
        }
        assertThat(service.currentList()).hasSize(1);
        assertThat(service.adminHistory(TermsType.SERVICE_TERMS, 0, 20).getContent())
                .extracting(v -> v.status()).containsExactlyInAnyOrder("CURRENT", "ARCHIVED");
    }

    @Test
    void 같은_버전의_동시초안_작성은_하나만_저장한다() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> create = () -> {
                start.await();
                try { service.create(command(TermsType.REVIEW_POLICY, "1")); return true; }
                catch (BusinessException expected) { return false; }
            };
            var a = executor.submit(create);
            var b = executor.submit(create);
            start.countDown();
            assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(service.adminHistory(TermsType.REVIEW_POLICY, 0, 20).getContent()).hasSize(1);
    }

    @Test
    void 버전키는_대소문자를_구분하고_미래시행일도_게시즉시_현재가_된다() {
        service.create(command(TermsType.POINT_TERMS, "V1"));
        var command = new TermsCommand(TermsType.POINT_TERMS, "포인트", "v1", LocalDate.of(2099, 1, 1),
                List.of(new TermsClause("제1조", "본문")));
        var draft = service.create(command);
        assertThat(service.publish(draft.termsId()).status()).isEqualTo("CURRENT");
        assertThat(service.currentDetail(draft.termsId()).effectiveDate()).isEqualTo(LocalDate.of(2099, 1, 1));
    }
    @Test
    void 같은_초안을_동시에_게시해도_두번째는_게시불변성에_걸린다() throws Exception {
        var draft = service.create(command(TermsType.SERVICE_POLICY, "1"));
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> publish = () -> {
                start.await();
                try { service.publish(draft.termsId()); return true; }
                catch (BusinessException expected) { return false; }
            };
            var a = executor.submit(publish);
            var b = executor.submit(publish);
            start.countDown();
            assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(service.currentList()).hasSize(1);
    }
}
