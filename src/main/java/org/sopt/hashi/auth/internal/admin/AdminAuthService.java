package org.sopt.hashi.auth.internal.admin;

import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.sopt.hashi.auth.code.AuthErrorCode;
import org.sopt.hashi.auth.internal.UserAuthService.TokenPair;
import org.sopt.hashi.auth.internal.jwt.AuthRoles;
import org.sopt.hashi.auth.internal.jwt.JwtProvider;
import org.sopt.hashi.auth.internal.token.RefreshTokenStore;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 어드민 인증 — ID/PW 로그인(ROLE_ADMIN 토큰 발급)과 로그아웃(리프레시 무효화).
 * 재발급(회전)은 유저와 공용 /api/v1/auth/reissue를 그대로 쓴다(role 보존·키 네임스페이스 분리).
 */
@Slf4j
@Service
public class AdminAuthService {

    private final AdminRepository adminRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final RefreshTokenStore refreshTokenStore;
    /** ID 미존재 시에도 같은 비용의 해시 비교를 수행하기 위한 더미 해시 — 인코더로 생성해 포맷·강도가 실제 해시와 일치한다. */
    private final String dummyPasswordHash;

    AdminAuthService(AdminRepository adminRepository,
                     PasswordEncoder passwordEncoder,
                     JwtProvider jwtProvider,
                     RefreshTokenStore refreshTokenStore) {
        this.adminRepository = adminRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtProvider = jwtProvider;
        this.refreshTokenStore = refreshTokenStore;
        this.dummyPasswordHash = passwordEncoder.encode("hashi-admin-timing-dummy");
    }

    /**
     * ID/PW를 검증하고 ROLE_ADMIN 토큰 쌍을 발급한다. 계정 열거 방지를 위해 ID 미존재·PW 불일치를
     * 단일 메시지로 응답하고, ID가 없어도 더미 해시와 비교해 **응답 시간 차이(타이밍 사이드채널)도 남기지 않는다**.
     */
    public TokenPair login(String loginId, String rawPassword) {
        Optional<Admin> admin = adminRepository.findByLoginId(loginId);
        String hashToCompare = admin.map(Admin::getPassword).orElse(dummyPasswordHash);
        boolean matches = passwordEncoder.matches(rawPassword, hashToCompare);
        if (admin.isEmpty() || !matches) {
            throw new BusinessException(AuthErrorCode.INVALID_CREDENTIALS);
        }
        Long adminId = admin.get().getId();
        String accessToken = jwtProvider.createAccessToken(adminId, AuthRoles.ADMIN);
        String refreshToken = jwtProvider.createRefreshToken(adminId, AuthRoles.ADMIN);
        refreshTokenStore.save(AuthRoles.ADMIN, adminId, refreshToken);
        // 로그인 요청은 토큰 없이 와서 MDC가 비어 있다 — 어드민 접속 기록은 이 로그가 유일하다 (loginId는 계정 정보라 제외)
        log.info("어드민 로그인 성공. adminId={}", adminId);
        return new TokenPair(accessToken, refreshToken);
    }

    /**
     * 리프레시 쿠키로 로그아웃한다 — 어드민 리프레시 토큰만 수용하고 세션(리프레시)을 무효화한다.
     * 액세스 토큰은 무상태 JWT라 만료까지 유효하다(블랙리스트는 후속 — auth.md 예정).
     */
    public void logout(String presentedRefreshToken) {
        JwtProvider.JwtClaims claims = jwtProvider.parse(presentedRefreshToken);
        if (!claims.isRefreshToken() || !AuthRoles.ADMIN.equals(claims.role())) {
            throw new BusinessException(AuthErrorCode.INVALID_TOKEN);
        }
        refreshTokenStore.revoke(AuthRoles.ADMIN, claims.subjectId());
    }
}
