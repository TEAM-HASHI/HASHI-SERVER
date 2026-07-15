package org.sopt.hashi.auth.internal.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 어드민 계정(ERD admin) — ID/PW 로그인 주체. password는 BCrypt 해시만 저장한다(평문 금지).
 * 가입 API가 없으므로 계정은 시드(DB insert)로만 생성된다.
 */
@Getter
@Entity
@Table(name = "admin",
        uniqueConstraints = @UniqueConstraint(name = "uk_admin_login_id", columnNames = "login_id"))
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class Admin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "login_id", length = 50, nullable = false)
    private String loginId;

    @Column(name = "password", length = 255, nullable = false)
    private String password;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
