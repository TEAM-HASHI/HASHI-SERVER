package org.sopt.hashi.auth.internal.account;

import java.util.Optional;
import org.sopt.hashi.auth.code.AuthErrorCode;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인증 계정 조회·연결·해제. 소셜 제공자 식별자와 회원(userId)의 매핑을 auth 모듈이 소유한다.
 */
@Service
public class AuthAccountService {

    private final AuthAccountRepository authAccountRepository;

    AuthAccountService(AuthAccountRepository authAccountRepository) {
        this.authAccountRepository = authAccountRepository;
    }

    /** 소셜 계정으로 가입된 회원의 userId를 조회한다. 미가입이면 empty. */
    public Optional<Long> findUserId(AuthProvider provider, String providerUserId) {
        return authAccountRepository.findByProviderAndProviderUserId(provider, providerUserId)
                .map(AuthAccount::getUserId);
    }

    /**
     * 소셜 계정을 회원에 연결한다. 호출자(온보딩)의 트랜잭션에 참여해 회원 생성과 원자적으로 커밋된다.
     * 동시 가입 경합은 (provider, provider_user_id) 유니크 제약이 최종 방어하며,
     * 위반은 500이 아니라 이미 가입됨(409) 도메인 에러로 변환한다.
     */
    @Transactional
    public void link(AuthProvider provider, String providerUserId, Long userId) {
        try {
            authAccountRepository.save(AuthAccount.link(userId, provider, providerUserId));
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(AuthErrorCode.ALREADY_LINKED_ACCOUNT, e);
        }
    }

    /**
     * 탈퇴 회원의 소셜 계정 연결을 삭제한다. 호출자(탈퇴)의 트랜잭션에 참여해 회원 삭제와 원자적으로 커밋되며,
     * 같은 소셜 계정으로 다시 로그인하면 미가입자로 판정돼 새 회원으로 온보딩한다.
     */
    @Transactional
    public void unlink(Long userId) {
        authAccountRepository.deleteByUserId(userId);
    }
}
