package org.sopt.hashi.exchangerate.domain;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.sopt.hashi.exchangerate.ExchangeRatePort.Quote;
import org.sopt.hashi.exchangerate.ExchangeRatePort.Revision;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ExchangeRateStore {
    private final JdbcTemplate db;

    public ExchangeRateStore(JdbcTemplate db) {
        this.db = db;
    }

    public Optional<Revision> latest(LocalDate day) {
        return db.query("""
                SELECT * FROM menu_exchange_revision WHERE applied_date<=?
                ORDER BY applied_date DESC,id DESC LIMIT 1
                """, this::revision, day).stream().findFirst();
    }

    /** 이월일은 적용일로 남기고 금액은 그날까지 가장 최근 은행 고시·수동 보정에서 읽는다. */
    public Optional<Quote> effectiveQuote(LocalDate day) {
        return db.query("""
                SELECT (SELECT MAX(applied_date) FROM menu_exchange_revision WHERE applied_date<=?) AS effective_date,
                       quoted_date, krw_per_100_jpy
                FROM menu_exchange_revision
                WHERE applied_date<=? AND source IN ('BANK', 'MANUAL')
                ORDER BY applied_date DESC, id DESC LIMIT 1
                """, (row, index) -> new Quote(row.getObject("effective_date", LocalDate.class),
                        row.getObject("quoted_date", LocalDate.class), row.getBigDecimal("krw_per_100_jpy")), day, day)
                .stream().findFirst();
    }

    @Transactional(readOnly = true)
    public Page<Revision> history(Pageable page) {
        List<Revision> revisions = db.query("SELECT * FROM menu_exchange_revision ORDER BY id DESC LIMIT ? OFFSET ?",
                this::revision, page.getPageSize(), page.getOffset());
        long total = db.queryForObject("SELECT COUNT(*) FROM menu_exchange_revision", Long.class);
        return new PageImpl<>(revisions, page, total);
    }

    @Transactional
    public boolean claim(LocalDate day) {
        lockDay(day);
        return db.update("""
                UPDATE menu_exchange_day SET attempts=attempts+1,retry_after=UTC_TIMESTAMP(6)+INTERVAL 15 MINUTE
                WHERE applied_date=? AND completed=FALSE AND attempts<3
                  AND (retry_after IS NULL OR retry_after<=UTC_TIMESTAMP(6))
                """, day) == 1;
    }

    @Transactional
    public void completeLookup(LocalDate day, Optional<BigDecimal> rate) {
        if (lockDay(day)) {
            return;
        }
        if (rate.isPresent()) {
            append(day, day, rate.get(), "BANK", null);
            completeDay(day);
        } else {
            effectiveQuote(day).filter(quote -> quote.appliedDate().isBefore(day))
                    .ifPresent(quote -> append(day, quote.quotedDate(), quote.krwPer100Jpy(), "CARRY", null));
        }
    }

    @Transactional
    public Revision correct(LocalDate day, LocalDate quotedDate, BigDecimal rate, long adminId) {
        lockDay(day);
        var same = latest(day).filter(r -> r.source().equals("MANUAL")
                && r.quote().appliedDate().equals(day) && r.quote().quotedDate().equals(quotedDate)
                && r.quote().krwPer100Jpy().compareTo(rate) == 0);
        if (same.isPresent()) {
            return same.get();
        }
        append(day, quotedDate, rate, "MANUAL", adminId);
        completeDay(day);
        return latest(day).orElseThrow();
    }

    private boolean lockDay(LocalDate day) {
        db.update("INSERT INTO menu_exchange_day(applied_date) VALUES (?) ON DUPLICATE KEY UPDATE applied_date=?",
                day, day);
        return Boolean.TRUE.equals(db.queryForObject(
                "SELECT completed FROM menu_exchange_day WHERE applied_date=? FOR UPDATE", Boolean.class, day));
    }

    private void completeDay(LocalDate day) {
        db.update("UPDATE menu_exchange_day SET completed=TRUE WHERE applied_date=?", day);
    }

    private void append(LocalDate day, LocalDate quote, BigDecimal rate, String source, Long adminId) {
        db.update("""
                INSERT INTO menu_exchange_revision(applied_date,quoted_date,krw_per_100_jpy,source,admin_id,created_at)
                VALUES (?,?,?,?,?,UTC_TIMESTAMP(6))
                """, day, quote, rate, source, adminId);
    }

    private Revision revision(ResultSet row, int index) throws SQLException {
        var quote = new Quote(row.getObject("applied_date", LocalDate.class),
                row.getObject("quoted_date", LocalDate.class), row.getBigDecimal("krw_per_100_jpy"));
        return new Revision(row.getLong("id"), quote, row.getString("source"), row.getObject("admin_id", Long.class),
                row.getObject("created_at", LocalDateTime.class));
    }
}
