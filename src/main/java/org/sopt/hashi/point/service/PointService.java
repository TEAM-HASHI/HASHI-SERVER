package org.sopt.hashi.point.service;

import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.point.PointSourceType;
import org.sopt.hashi.point.code.PointErrorCode;
import org.sopt.hashi.point.domain.PointAccount;
import org.sopt.hashi.point.domain.PointAccountRepository;
import org.sopt.hashi.point.domain.PointTransaction;
import org.sopt.hashi.point.domain.PointTransactionRepository;
import org.sopt.hashi.point.domain.PointTransactionType;
import org.sopt.hashi.point.dto.PointBalanceResponse;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 포인트 계정·원장 로직. 모든 변동은 잔액 갱신과 원장 기록을 한 트랜잭션에서 함께 수행한다.
 * 변동 메서드는 호출자(예약 생성 등)의 트랜잭션에 참여해 함께 커밋/롤백된다(§8).
 */
@Service
public class PointService {

    private final PointAccountRepository pointAccountRepository;
    private final PointTransactionRepository pointTransactionRepository;
    private final CurrentUserProvider currentUserProvider;

    public PointService(PointAccountRepository pointAccountRepository,
                        PointTransactionRepository pointTransactionRepository,
                        CurrentUserProvider currentUserProvider) {
        this.pointAccountRepository = pointAccountRepository;
        this.pointTransactionRepository = pointTransactionRepository;
        this.currentUserProvider = currentUserProvider;
    }

    /** 적립 — 계정이 없으면 lazy 생성 후 잔액 증가 + 원장 기록. */
    @Transactional
    public void earn(Long userId, long amount, String reason, PointSourceType sourceType, Long sourceId) {
        validateAmount(amount);
        PointAccount account = getOrCreateAccount(userId);
        account.earn(amount);
        pointTransactionRepository.save(PointTransaction.earn(account.getId(), amount, reason, sourceType, sourceId));
    }

    /** 차감 — 잔액 부족 시 INSUFFICIENT_BALANCE. 잔액 감소 + 원장 기록. */
    @Transactional
    public void use(Long userId, long amount, String reason, PointSourceType sourceType, Long sourceId) {
        validateAmount(amount);
        PointAccount account = getOrCreateAccount(userId);
        account.use(amount);
        pointTransactionRepository.save(PointTransaction.use(account.getId(), amount, reason, sourceType, sourceId));
    }

    /**
     * 복원 — 같은 출처의 차감 원장에서 금액을 찾아 되돌린다.
     * 이미 복원된 출처면 ALREADY_RESTORED(중복 복원은 버그·재시도이므로 조용히 무시하지 않고 드러낸다).
     */
    @Transactional
    public void restore(Long userId, PointSourceType sourceType, Long sourceId) {
        PointAccount account = pointAccountRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(PointErrorCode.RESTORE_TARGET_NOT_FOUND));
        PointTransaction useTransaction = pointTransactionRepository
                .findByTypeAndSourceTypeAndSourceId(PointTransactionType.USE, sourceType, sourceId)
                .filter(tx -> tx.getPointAccountId().equals(account.getId()))
                .orElseThrow(() -> new BusinessException(PointErrorCode.RESTORE_TARGET_NOT_FOUND));
        if (pointTransactionRepository.existsByTypeAndSourceTypeAndSourceId(
                PointTransactionType.RESTORE, sourceType, sourceId)) {
            throw new BusinessException(PointErrorCode.ALREADY_RESTORED);
        }
        account.restore(useTransaction.getAmount());
        pointTransactionRepository.save(PointTransaction.restore(
                account.getId(), useTransaction.getAmount(), sourceType, sourceId));
    }

    /** 잔액 조회 — 계정이 없으면 0(아직 포인트 발생 이력이 없는 사용자). */
    @Transactional(readOnly = true)
    public long getBalance(Long userId) {
        return pointAccountRepository.findByUserId(userId)
                .map(PointAccount::getBalance)
                .orElse(0L);
    }

    /** 현재 로그인 사용자의 잔액 조회(내 포인트 API용 — auth.md §2). */
    @Transactional(readOnly = true)
    public PointBalanceResponse getMyBalance() {
        return new PointBalanceResponse(getBalance(currentUserProvider.currentUserId()));
    }

    private void validateAmount(long amount) {
        if (amount < 1) {
            throw new BusinessException(PointErrorCode.INVALID_AMOUNT);
        }
    }

    /** 계정 lazy 생성. 동시 생성 경합은 uk_point_account_user가 최종 방어하며, 충돌 시 기존 계정을 재조회한다. */
    private PointAccount getOrCreateAccount(Long userId) {
        return pointAccountRepository.findByUserId(userId)
                .orElseGet(() -> createAccount(userId));
    }

    private PointAccount createAccount(Long userId) {
        try {
            return pointAccountRepository.saveAndFlush(PointAccount.open(userId));
        } catch (DataIntegrityViolationException e) {
            // 동시 요청이 먼저 계정을 만든 경우 — 유니크 제약이 막아준 것이므로 기존 계정으로 진행한다
            return pointAccountRepository.findByUserId(userId)
                    .orElseThrow(() -> e);
        }
    }
}
