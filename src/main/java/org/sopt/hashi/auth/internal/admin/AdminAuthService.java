package org.sopt.hashi.auth.internal.admin;

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
@Service
public class AdminAuthService {

    private final AdminRepository adminRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final RefreshTokenStore refreshTokenStore;

    AdminAuthService(AdminRepository adminRepository,
                     PasswordEncoder passwordEncoder,
                     JwtProvider jwtProvider,
                     RefreshTokenStore refreshTokenStore) {
        this.adminRepository = adminRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtProvider = jwtProvider;
        this.refreshTokenStore = refreshTokenStore;
    }

    /** ID/PW를 검증하고 ROLE_ADMIN 토큰 쌍을 발급한다. ID 미존재·PW 불일치를 구분하지 않는다(계정 열거 방지). */
    public TokenPair login(String loginId, String rawPassword) {
        Admin admin = adminRepository.findByLoginId(loginId)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.INVALID_CREDENTIALS));
        if (!passwordEncoder.matches(rawPassword, admin.getPassword())) {
            throw new BusinessException(AuthErrorCode.INVALID_CREDENTIALS);
        }
        String accessToken = jwtProvider.createAccessToken(admin.getId(), AuthRoles.ADMIN);
        String refreshToken = jwtProvider.createRefreshToken(admin.getId(), AuthRoles.ADMIN);
        refreshTokenStore.save(AuthRoles.ADMIN, admin.getId(), refreshToken);
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
