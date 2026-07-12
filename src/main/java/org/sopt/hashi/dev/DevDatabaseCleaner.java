package org.sopt.hashi.dev;

import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 개발용 DB 데이터 초기화 — local·dev 프로필에서만 빈이 등록된다(운영에는 존재 자체가 없음).
 * 스키마는 그대로 두고 데이터만 비우는 개발 편의 도구라, 도메인 로직을 거치지 않고 테이블을 직접
 * TRUNCATE 한다(AUTO_INCREMENT도 1로 초기화). 테이블 목록은 information_schema에서 동적으로 읽어
 * 스키마가 늘어나도 코드 수정이 필요 없다.
 *
 * <p>보존 대상: flyway_schema_history(마이그레이션 이력), admin(시드된 어드민 계정 — 지우면 어드민
 * 로그인이 불가해진다). Redis(리프레시·온보딩 토큰)는 건드리지 않는다.
 */
@Profile({"local", "dev"})
@Service
public class DevDatabaseCleaner {

    private static final List<String> PRESERVED_TABLES = List.of("flyway_schema_history", "admin");

    private final JdbcTemplate jdbcTemplate;

    public DevDatabaseCleaner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 보존 대상을 제외한 모든 테이블을 비우고, 비운 테이블 이름 목록을 반환한다. */
    @Transactional
    public List<String> clearAll() {
        List<String> tables = jdbcTemplate.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'
                ORDER BY table_name
                """, String.class).stream()
                .filter(table -> !PRESERVED_TABLES.contains(table.toLowerCase()))
                .toList();

        // TRUNCATE는 FK 자식→부모 순서를 따지지 않아도 되게 검사만 잠시 끈다(모듈 내부 FK 대응).
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        try {
            for (String table : tables) {
                jdbcTemplate.execute("TRUNCATE TABLE `" + table + "`");
            }
        } finally {
            jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
        return tables;
    }
}
