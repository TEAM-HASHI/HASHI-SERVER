package org.sopt.hashi.user.domain;

import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.user.code.UserErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class SignupPhoneStore {
    private final JdbcTemplate db;

    public SignupPhoneStore(JdbcTemplate db) {
        this.db = db;
    }

    @Transactional
    public void issue(byte[] actorBucket, byte[] session, byte[] phone, String id) {
        limit(actorBucket);
        db.update("""
                INSERT INTO signup_phone_challenge(session_digest,phone_digest,challenge_id,expires_at)
                VALUES (?,?,?,UTC_TIMESTAMP(6) + INTERVAL 5 MINUTE) AS fresh
                ON DUPLICATE KEY UPDATE phone_digest=fresh.phone_digest, challenge_id=fresh.challenge_id,
                    expires_at=fresh.expires_at, attempts=0, verified=FALSE
                """, session, phone, id);
    }

    private void limit(byte[] bucket) {
        db.update("""
                INSERT INTO signup_phone_rate_limit(bucket,expires_at,hits)
                VALUES (?,UTC_TIMESTAMP(6) + INTERVAL 5 MINUTE,1)
                ON DUPLICATE KEY UPDATE hits=IF(expires_at<=UTC_TIMESTAMP(6),1,hits+1),
                    expires_at=IF(expires_at<=UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) + INTERVAL 5 MINUTE,expires_at)
                """, bucket);
        int hits = db.queryForObject("SELECT hits FROM signup_phone_rate_limit WHERE bucket=?", Integer.class, bucket);
        if (hits > 3) {
            throw new BusinessException(UserErrorCode.PHONE_PROOF_LIMIT);
        }
    }

    /** 외부 조회 전에 시도 횟수를 커밋해 실패·동시 요청도 한도에 포함한다. */
    @Transactional
    public boolean beginCheck(byte[] session, byte[] phone, String id) {
        var states = db.query("""
                SELECT verified, attempts FROM signup_phone_challenge
                WHERE session_digest=? AND phone_digest=? AND challenge_id=? AND expires_at>UTC_TIMESTAMP(6)
                FOR UPDATE
                """, (rs, n) -> new State(rs.getBoolean(1), rs.getInt(2)), session, phone, id);
        if (states.isEmpty()) {
            throw new BusinessException(UserErrorCode.PHONE_PROOF_REQUIRED);
        }
        var state = states.getFirst();
        if (state.verified()) {
            return false;
        }
        if (state.attempts() >= 5) {
            throw new BusinessException(UserErrorCode.PHONE_PROOF_LIMIT);
        }
        db.update("UPDATE signup_phone_challenge SET attempts=attempts+1 WHERE session_digest=?", session);
        return true;
    }
    private record State(boolean verified, int attempts) {}

    public void markVerified(byte[] session, byte[] phone, String id) {
        int rows = db.update("""
                UPDATE signup_phone_challenge SET verified=TRUE
                WHERE session_digest=? AND phone_digest=? AND challenge_id=? AND expires_at>UTC_TIMESTAMP(6)
                """, session, phone, id);
        if (rows == 0) {
            throw new BusinessException(UserErrorCode.PHONE_PROOF_REQUIRED);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(byte[] session, byte[] phone) {
        int rows = db.update("""
                DELETE FROM signup_phone_challenge WHERE session_digest=? AND phone_digest=?
                    AND verified=TRUE AND expires_at>UTC_TIMESTAMP(6)
                """, session, phone);
        if (rows == 0) {
            throw new BusinessException(UserErrorCode.PHONE_PROOF_REQUIRED);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean claimReward(byte[] phone) {
        try {
            db.update("INSERT INTO invite_rewarded_phone(phone_digest) VALUES (?)", phone);
            return true;
        } catch (DuplicateKeyException alreadyRewarded) {
            return false;
        }
    }

    public void purgeExpired() {
        db.update("DELETE FROM signup_phone_challenge WHERE expires_at<=UTC_TIMESTAMP(6) ORDER BY expires_at LIMIT 1000");
        db.update("DELETE FROM signup_phone_rate_limit WHERE expires_at<=UTC_TIMESTAMP(6) ORDER BY expires_at LIMIT 1000");
    }
}
