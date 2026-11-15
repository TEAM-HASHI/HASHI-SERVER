package org.sopt.hashi.magazine.migration;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class MagazineMediaBackfillCheckpointStore {

    private static final String INSERT_IF_ABSENT_SQL = """
            INSERT INTO magazine_media_backfill_checkpoint (
                run_id, target, mode, status, upper_bound_id, cursor_id,
                scanned_count, prepared_count, attached_count, skipped_count, failed_count
            ) VALUES (?, ?, ?, 'PAUSED', ?, 0, 0, 0, 0, 0, 0)
            ON DUPLICATE KEY UPDATE run_id = run_id
            """;
    private static final String SELECT_SQL = """
            SELECT run_id, target, mode, status, upper_bound_id, cursor_id,
                   lease_token, lease_until, scanned_count, prepared_count,
                   attached_count, skipped_count, failed_count
            FROM magazine_media_backfill_checkpoint
            WHERE run_id = ?
            """;
    private static final String SELECT_FOR_UPDATE_SQL = SELECT_SQL + " FOR UPDATE";

    private final JdbcTemplate jdbcTemplate;

    MagazineMediaBackfillCheckpointStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public Acquisition acquire(
            UUID runId,
            MagazineMediaBackfillTarget target,
            MagazineMediaBackfillMode mode,
            long initialUpperBound,
            Duration leaseDuration
    ) {
        requirePersistentMode(mode);
        if (initialUpperBound < 0) {
            throw new IllegalArgumentException("initialUpperBound must not be negative");
        }
        jdbcTemplate.update(
                INSERT_IF_ABSENT_SQL,
                runId.toString(), target.name(), mode.name(), initialUpperBound
        );
        Snapshot snapshot = findForUpdate(runId);
        validateIdentity(snapshot, target, mode);
        if (snapshot.status() == Status.COMPLETED) {
            return new Acquisition(AcquisitionState.COMPLETED, null, snapshot);
        }

        LocalDateTime databaseNow = databaseNow();
        boolean heldByAnotherWorker = snapshot.status() == Status.RUNNING
                && snapshot.leaseUntil() != null
                && snapshot.leaseUntil().isAfter(databaseNow);
        if (heldByAnotherWorker) {
            return new Acquisition(AcquisitionState.BUSY, null, snapshot);
        }

        UUID leaseToken = UUID.randomUUID();
        int updated = jdbcTemplate.update("""
                        UPDATE magazine_media_backfill_checkpoint
                        SET status = 'RUNNING',
                            lease_token = ?,
                            lease_until = TIMESTAMPADD(MICROSECOND, ?, CURRENT_TIMESTAMP(6)),
                            updated_at = CURRENT_TIMESTAMP(6)
                        WHERE run_id = ?
                        """,
                leaseToken.toString(), leaseMicros(leaseDuration), runId.toString());
        if (updated != 1) {
            throw new MagazineMediaBackfillLeaseLostException();
        }
        Snapshot acquired = findForUpdate(runId);
        return new Acquisition(
                AcquisitionState.ACQUIRED,
                new Lease(runId, leaseToken, target, mode, acquired.upperBoundId()),
                acquired
        );
    }

    @Transactional
    public void recordProgress(
            Lease lease,
            long nextCursor,
            MagazineMediaBackfillOutcome outcome,
            Duration leaseDuration
    ) {
        Objects.requireNonNull(lease, "lease is required");
        Objects.requireNonNull(outcome, "outcome is required");
        // MySQL CURRENT_TIMESTAMP는 statement 시작 시각이다. 잠금 대기 후 별도 UPDATE에서 만료를 판단한다.
        findForUpdate(lease.runId());
        CounterDelta delta = CounterDelta.from(outcome);
        int updated = jdbcTemplate.update("""
                        UPDATE magazine_media_backfill_checkpoint
                        SET cursor_id = ?,
                            scanned_count = scanned_count + 1,
                            prepared_count = prepared_count + ?,
                            attached_count = attached_count + ?,
                            skipped_count = skipped_count + ?,
                            failed_count = failed_count + ?,
                            lease_until = TIMESTAMPADD(MICROSECOND, ?, CURRENT_TIMESTAMP(6)),
                            updated_at = CURRENT_TIMESTAMP(6)
                        WHERE run_id = ?
                          AND target = ?
                          AND mode = ?
                          AND status = 'RUNNING'
                          AND lease_token = ?
                          AND lease_until > CURRENT_TIMESTAMP(6)
                          AND cursor_id < ?
                          AND upper_bound_id >= ?
                        """,
                nextCursor,
                delta.prepared(), delta.attached(), delta.skipped(), delta.failed(),
                leaseMicros(leaseDuration),
                lease.runId().toString(), lease.target().name(), lease.mode().name(),
                lease.token().toString(), nextCursor, nextCursor);
        if (updated != 1) {
            throw new MagazineMediaBackfillLeaseLostException();
        }
    }

    @Transactional
    public Snapshot complete(Lease lease) {
        Objects.requireNonNull(lease, "lease is required");
        findForUpdate(lease.runId());
        int updated = jdbcTemplate.update("""
                        UPDATE magazine_media_backfill_checkpoint
                        SET status = 'COMPLETED',
                            cursor_id = upper_bound_id,
                            lease_token = NULL,
                            lease_until = NULL,
                            updated_at = CURRENT_TIMESTAMP(6)
                        WHERE run_id = ?
                          AND target = ?
                          AND mode = ?
                          AND status = 'RUNNING'
                          AND lease_token = ?
                          AND lease_until > CURRENT_TIMESTAMP(6)
                        """,
                lease.runId().toString(), lease.target().name(), lease.mode().name(),
                lease.token().toString());
        if (updated != 1) {
            throw new MagazineMediaBackfillLeaseLostException();
        }
        return findForUpdate(lease.runId());
    }

    @Transactional
    public boolean pause(Lease lease) {
        Objects.requireNonNull(lease, "lease is required");
        // 잠금 대기 중 lease가 만료될 수 있으므로, 잠금을 얻은 뒤 별도 UPDATE 시각으로 다시 판단한다.
        findForUpdate(lease.runId());
        return jdbcTemplate.update("""
                        UPDATE magazine_media_backfill_checkpoint
                        SET status = 'PAUSED',
                            lease_token = NULL,
                            lease_until = NULL,
                            updated_at = CURRENT_TIMESTAMP(6)
                        WHERE run_id = ?
                          AND target = ?
                          AND mode = ?
                          AND status = 'RUNNING'
                          AND lease_token = ?
                          AND lease_until > CURRENT_TIMESTAMP(6)
                        """,
                lease.runId().toString(), lease.target().name(), lease.mode().name(),
                lease.token().toString()) == 1;
    }

    @Transactional(readOnly = true)
    public Snapshot find(UUID runId) {
        return jdbcTemplate.queryForObject(SELECT_SQL, this::mapSnapshot, runId.toString());
    }

    private Snapshot findForUpdate(UUID runId) {
        return jdbcTemplate.queryForObject(SELECT_FOR_UPDATE_SQL, this::mapSnapshot, runId.toString());
    }

    private Snapshot mapSnapshot(ResultSet resultSet, int rowNumber) throws SQLException {
        Timestamp leaseUntil = resultSet.getTimestamp("lease_until");
        return new Snapshot(
                UUID.fromString(resultSet.getString("run_id")),
                MagazineMediaBackfillTarget.valueOf(resultSet.getString("target")),
                MagazineMediaBackfillMode.valueOf(resultSet.getString("mode")),
                Status.valueOf(resultSet.getString("status")),
                resultSet.getLong("upper_bound_id"),
                resultSet.getLong("cursor_id"),
                leaseUntil == null ? null : leaseUntil.toLocalDateTime(),
                resultSet.getLong("scanned_count"),
                resultSet.getLong("prepared_count"),
                resultSet.getLong("attached_count"),
                resultSet.getLong("skipped_count"),
                resultSet.getLong("failed_count")
        );
    }

    private void validateIdentity(
            Snapshot snapshot,
            MagazineMediaBackfillTarget target,
            MagazineMediaBackfillMode mode
    ) {
        if (snapshot.target() != target || snapshot.mode() != mode) {
            throw new IllegalArgumentException("runId is already assigned to a different backfill execution");
        }
    }

    private LocalDateTime databaseNow() {
        return jdbcTemplate.queryForObject("SELECT CURRENT_TIMESTAMP(6)", LocalDateTime.class);
    }

    private long leaseMicros(Duration duration) {
        Objects.requireNonNull(duration, "lease duration is required");
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("lease duration must be positive");
        }
        return duration.toNanos() / 1_000L;
    }

    private void requirePersistentMode(MagazineMediaBackfillMode mode) {
        if (mode == null || !mode.usesCheckpoint()) {
            throw new IllegalArgumentException("only PREPARE and ATTACH use checkpoints");
        }
    }

    enum AcquisitionState {
        ACQUIRED,
        BUSY,
        COMPLETED
    }

    enum Status {
        PAUSED,
        RUNNING,
        COMPLETED
    }

    record Acquisition(AcquisitionState state, Lease lease, Snapshot snapshot) {

        Acquisition {
            Objects.requireNonNull(state, "acquisition state is required");
            Objects.requireNonNull(snapshot, "checkpoint snapshot is required");
            if ((state == AcquisitionState.ACQUIRED) != (lease != null)) {
                throw new IllegalArgumentException("only acquired checkpoints have a lease");
            }
        }
    }

    record Lease(
            UUID runId,
            UUID token,
            MagazineMediaBackfillTarget target,
            MagazineMediaBackfillMode mode,
            long upperBoundId
    ) {

        Lease {
            Objects.requireNonNull(runId, "runId is required");
            Objects.requireNonNull(token, "lease token is required");
            Objects.requireNonNull(target, "target is required");
            Objects.requireNonNull(mode, "mode is required");
            if (!mode.usesCheckpoint() || upperBoundId < 0) {
                throw new IllegalArgumentException("invalid magazine media backfill lease");
            }
        }

        @Override
        public String toString() {
            return "MagazineMediaBackfillLease[redacted]";
        }
    }

    record Snapshot(
            UUID runId,
            MagazineMediaBackfillTarget target,
            MagazineMediaBackfillMode mode,
            Status status,
            long upperBoundId,
            long cursorId,
            LocalDateTime leaseUntil,
            long scannedCount,
            long preparedCount,
            long attachedCount,
            long skippedCount,
            long failedCount
    ) {

        Snapshot {
            Objects.requireNonNull(runId, "runId is required");
            Objects.requireNonNull(target, "target is required");
            Objects.requireNonNull(mode, "mode is required");
            Objects.requireNonNull(status, "status is required");
        }

        @Override
        public String toString() {
            return "MagazineMediaBackfillCheckpoint[redacted]";
        }
    }

    private record CounterDelta(long prepared, long attached, long skipped, long failed) {

        private static CounterDelta from(MagazineMediaBackfillOutcome outcome) {
            return switch (outcome) {
                case PREPARED -> new CounterDelta(1, 0, 0, 0);
                case ATTACHED -> new CounterDelta(0, 1, 0, 0);
                case SKIPPED -> new CounterDelta(0, 0, 1, 0);
                case FAILED -> new CounterDelta(0, 0, 0, 1);
            };
        }
    }
}
