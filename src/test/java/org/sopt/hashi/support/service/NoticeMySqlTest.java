package org.sopt.hashi.support.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.config.TimeConfig;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.support.NoticeBlock;
import org.sopt.hashi.support.NoticeCommand;
import org.sopt.hashi.support.NoticeInfo;
import org.sopt.hashi.support.domain.NoticeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({NoticeService.class, NoticeContentCodec.class, ObjectMapper.class, TimeConfig.class, org.sopt.hashi.config.JpaAuditingConfig.class})
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NoticeMySqlTest {
    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("hashi").withUsername("hashi").withPassword("hashi");
    @Autowired NoticeService service;
    @Autowired NoticeRepository repository;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoBean MediaPort mediaPort;

    @BeforeEach
    void 정리() {
        repository.deleteAll();
        reset(mediaPort);
    }

    private NoticeCommand command(String title, List<UUID> ids) {
        return new NoticeCommand(title, List.of(new NoticeBlock(NoticeBlock.Type.PARAGRAPH,
                List.of(List.of(new NoticeBlock.Span("본문", false, null))))), ids);
    }

    @Test
    void 관리자_목록은_삭제를_제외하고_페이지와_전체_건수를_반환한다() {
        List<Long> ids = java.util.stream.IntStream.range(0, 23)
                .mapToObj(i -> service.create(command("공지 " + i, List.of())).noticeId()).toList();
        service.delete(ids.getFirst());
        var first = service.adminList(0, 20);
        var last = service.adminList(1, 20);
        assertThat(first.getTotalElements()).isEqualTo(22);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.getContent()).hasSize(20);
        assertThat(first.getContent().getFirst().noticeId()).isEqualTo(ids.getLast());
        assertThat(last.getContent()).extracting(NoticeInfo::noticeId).containsExactly(ids.get(2), ids.get(1));
        assertThat(service.adminList(2, 20).getContent()).isEmpty();
        assertThat(service.adminList(-1, 0).getSize()).isEqualTo(20);
        assertThat(service.adminList(0, 1000).getSize()).isEqualTo(100);
    }

    @Test
    void 초안의_작성과_수정_시각은_게시_시각과_별도로_저장한다() {
        NoticeInfo created = service.create(command("초안", List.of()));
        assertThat(created.createdAt()).isNotNull();
        assertThat(created.updatedAt()).isNotNull();
        assertThat(created.publishedAt()).isNull();
        jdbc.update("UPDATE support_notice SET updated_at = '2000-01-01 00:00:00' WHERE id = ?", created.noticeId());
        NoticeInfo updated = service.update(created.noticeId(), command("변경한 초안", List.of()));
        assertThat(updated.createdAt()).isEqualTo(created.createdAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThat(updated.updatedAt()).isAfter(java.time.LocalDateTime.of(2000, 1, 1, 0, 0));
        assertThat(updated.publishedAt()).isNull();
        assertThat(updated.lastModifiedAt()).isNull();
        assertThat(service.adminDetail(created.noticeId()).updatedAt())
                .isEqualTo(updated.updatedAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }

    @Test
    void 초안은_숨기고_게시와_수정일을_구분하며_삭제를_숨긴다() {
        Long id = service.create(command("초안", List.of())).noticeId();
        assertThat(service.list(null).notices()).isEmpty();
        assertThatThrownBy(() -> service.detail(id)).isInstanceOf(BusinessException.class);
        service.update(id, command("게시 전 수정", List.of()));
        NoticeInfo published = service.publish(id);
        assertThat(published.lastModifiedAt()).isEqualTo(published.publishedAt());
        NoticeInfo unchanged = service.update(id, command("게시 전 수정", List.of()));
        assertThat(unchanged.lastModifiedAt()).isEqualTo(published.publishedAt());
        NoticeInfo updated = service.update(id, command("게시 후 수정", List.of()));
        assertThat(updated.publishedAt()).isEqualTo(published.publishedAt());
        assertThat(updated.lastModifiedAt()).isAfterOrEqualTo(published.publishedAt());
        assertThat(service.publish(id).publishedAt()).isEqualTo(published.publishedAt());
        service.delete(id);
        assertThat(service.list(null).notices()).isEmpty();
        assertThatThrownBy(() -> service.detail(id)).isInstanceOf(BusinessException.class);
    }

    @Test
    void 목록은_10개_cursor로_안정적으로_이어지고_media를_조회하지_않는다() {
        for (int i = 0; i < 23; i++) {
            service.publish(service.create(command("공지" + i, List.of())).noticeId());
        }
        jdbc.update("UPDATE support_notice SET published_at = '2026-10-01 10:00:00.123456'");
        reset(mediaPort);
        var first = service.list(null);
        var second = service.list(first.nextCursor());
        var last = service.list(second.nextCursor());
        assertThat(first.notices()).hasSize(10);
        assertThat(first.notices()).extracting(org.sopt.hashi.support.dto.NoticeListResponse.Item::noticeId)
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(second.notices()).hasSize(10);
        assertThat(last.notices()).hasSize(3);
        assertThat(last.hasNext()).isFalse();
        assertThat(first.notices()).doesNotContainAnyElementsOf(second.notices());
        verifyNoInteractions(mediaPort);
        assertThatThrownBy(() -> service.list("invalid")).isInstanceOf(BusinessException.class);
    }

    @Test
    void 이미지_순서변경은_unique충돌없이_저장하고_실패하면_게시내용을_보존한다() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Long id = service.create(command("원본", List.of(a, b))).noticeId();
        service.publish(id);
        service.update(id, command("순서", List.of(b, a)));
        assertThat(service.detail(id).images()).extracting(NoticeInfo.Attachment::assetId)
                .containsExactly(b, a);
        doThrow(new IllegalStateException("synthetic media failure"))
                .when(mediaPort).reconcileBindings(any(), any());
        assertThatThrownBy(() -> service.update(id, command("실패", List.of(UUID.randomUUID()))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(service.detail(id).title()).isEqualTo("순서");
        assertThat(service.detail(id).images()).extracting(NoticeInfo.Attachment::assetId)
                .containsExactly(b, a);
        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(IllegalStateException.class);
        assertThat(service.detail(id).status()).isEqualTo("PUBLISHED");
    }
}
