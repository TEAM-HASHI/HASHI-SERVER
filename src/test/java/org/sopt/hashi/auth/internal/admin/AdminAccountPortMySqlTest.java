package org.sopt.hashi.auth.internal.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.auth.ActorType;
import org.sopt.hashi.auth.AdminAccountPort;
import org.sopt.hashi.auth.CurrentActor;
import org.sopt.hashi.auth.CurrentActorProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AdminAccountPortImpl.class)
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdminAccountPortMySqlTest {
    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("admin_display").withUsername("hashi").withPassword("hashi")
            .withCommand("--log-bin-trust-function-creators=1");

    @Autowired AdminAccountPort port;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean CurrentActorProvider actors;

    @Test
    void 실제_쿼리는_현재_로그인ID를_반환하고_삭제된_계정은_제외한다() {
        given(actors.currentActor()).willReturn(new CurrentActor(ActorType.ADMIN, 1L));
        jdbc.update("INSERT INTO admin(login_id, password, created_at) VALUES (?, ?, NOW())",
                "display_test_admin", "test-only-password-hash");
        Long id = jdbc.queryForObject("SELECT id FROM admin WHERE login_id=?", Long.class, "display_test_admin");

        assertThat(port.findLoginIds(Set.of(id))).containsExactlyEntriesOf(Map.of(id, "display_test_admin"));

        jdbc.update("UPDATE admin SET login_id=? WHERE id=?", "renamed_test_admin", id);
        assertThat(port.findLoginIds(Set.of(id))).containsExactlyEntriesOf(Map.of(id, "renamed_test_admin"));

        jdbc.update("DELETE FROM admin WHERE id=?", id);
        assertThat(port.findLoginIds(Set.of(id))).isEmpty();
    }
}
