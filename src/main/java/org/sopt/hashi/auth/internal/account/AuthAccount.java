package org.sopt.hashi.auth.internal.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 인증 계정 — 소셜 제공자 식별자(provider + provider_user_id)를 회원(user_id)에 매핑한다.
 * user_id는 users를 FK 없이 ID로만 참조한다(모듈 간 FK 금지, architecture.md §5).
 */
@Getter
@Entity
@Table(name = "auth_account",
        uniqueConstraints = @UniqueConstraint(columnNames = {"provider", "provider_user_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class AuthAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", length = 20, nullable = false)
    private AuthProvider provider;

    @Column(name = "provider_user_id", length = 100, nullable = false)
    private String providerUserId;

    private AuthAccount(Long userId, AuthProvider provider, String providerUserId) {
        this.userId = userId;
        this.provider = provider;
        this.providerUserId = providerUserId;
    }

    static AuthAccount link(Long userId, AuthProvider provider, String providerUserId) {
        return new AuthAccount(userId, provider, providerUserId);
    }
}
