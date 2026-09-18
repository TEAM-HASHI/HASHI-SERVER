package org.sopt.hashi.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.sopt.hashi.BaseTimeEntity;

/**
 * 회원. 소셜 OAuth로 인증하고 온보딩(프로필 입력)으로 가입이 완료된다.
 * 인증 제공자(카카오 등) 식별자는 이 테이블이 아니라 auth 모듈의 auth_account가 보관한다.
 * 삭제(탈퇴)는 soft delete(deleted=true)이며 삭제된 회원은 전역 필터로 모든 조회에서 제외된다 —
 * 호출 측은 조회 실패를 "탈퇴한 회원"으로 처리한다. nickname·email·phone 유니크 제약이 남으므로
 * 탈퇴 기능 구현 시 해당 컬럼 익명화가 함께 필요하다.
 */
@Getter
@Entity
@Table(name = "users",   // user는 예약어 충돌 여지가 있어 복수형 사용
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_users_nickname", columnNames = "nickname"),
                @UniqueConstraint(name = "uk_users_email", columnNames = "email"),
                @UniqueConstraint(name = "uk_users_phone", columnNames = "phone")
        })
@SQLDelete(sql = "UPDATE users SET deleted = true WHERE id = ?")
@SQLRestriction("deleted = false")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nickname", length = 50, nullable = false)
    private String nickname;

    @Column(name = "name_eng", length = 20)
    private String nameEng;

    @Column(name = "birth_date", nullable = false)
    private LocalDate birthDate;

    /** 전화번호는 앞자리 0·11자리를 보존해야 하므로 정수가 아닌 문자열로 저장한다. */
    @Column(name = "phone", length = 20, nullable = false)
    private String phone;

    @Column(name = "email", length = 255, nullable = false)
    private String email;

    /** 프로필 사진 — S3 object key만 저장한다(presigned URL 저장 금지, coding-style §4-2). */
    @Column(name = "profile_image_key", length = 500)
    private String profileImageKey;

    /** 신규 media 파이프라인의 public asset ID. media 내부 PK나 JPA 관계는 저장하지 않는다. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "profile_image_asset_id", length = 36, unique = true)
    private UUID profileImageAssetId;

    @Column(name = "deleted", nullable = false)
    private boolean deleted;

    private User(String nickname, String nameEng, LocalDate birthDate,
                 String phone, String email, String profileImageKey, UUID profileImageAssetId) {
        this.nickname = nickname;
        this.nameEng = nameEng;
        this.birthDate = birthDate;
        this.phone = phone;
        this.email = email;
        this.profileImageKey = profileImageKey;
        this.profileImageAssetId = profileImageAssetId;
        this.deleted = false;
    }

    /** 온보딩 완료로 가입한다. */
    public static User onboard(String nickname, String nameEng, LocalDate birthDate,
                               String phone, String email, String profileImageKey) {
        return onboard(nickname, nameEng, birthDate, phone, email, profileImageKey, null);
    }

    /** legacy key 또는 public asset ID를 보관해 온보딩을 완료한다. 둘 다 null이면 기본 프로필이다. */
    public static User onboard(String nickname, String nameEng, LocalDate birthDate,
                               String phone, String email, String profileImageKey,
                               UUID profileImageAssetId) {
        if (profileImageKey != null && profileImageAssetId != null) {
            throw new IllegalArgumentException("profile image sources are mutually exclusive");
        }
        return new User(
                nickname, nameEng, birthDate, phone, email,
                profileImageKey, profileImageAssetId);
    }

    /** 온보딩에서 media 소유권 인계·claim이 성공한 뒤 빈 프로필 슬롯에 연결한다. */
    public void assignOnboardingProfileImage(UUID assetId) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        if (profileImageKey != null || profileImageAssetId != null) {
            throw new IllegalStateException("onboarding profile image is already assigned");
        }
        profileImageAssetId = assetId;
    }
}
