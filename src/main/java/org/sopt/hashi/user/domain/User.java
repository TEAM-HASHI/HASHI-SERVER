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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.type.SqlTypes;
import org.sopt.hashi.BaseTimeEntity;

/**
 * 회원. 소셜 OAuth로 인증하고 온보딩(프로필 입력)으로 가입이 완료된다.
 * 인증 제공자(카카오 등) 식별자는 이 테이블이 아니라 auth 모듈의 auth_account가 보관한다.
 * 탈퇴는 soft delete(deleted=true)다. 탈퇴 회원의 리뷰도 작성자(익명 닉네임)와 함께 보여줘야 하므로 전역 필터 없이
 * 조회마다 deleted 조건을 명시한다 — 탈퇴 회원을 포함하는 조회는 {@code UserPort.findProfiles}뿐이다.
 * nickname·email·phone은 유니크라 탈퇴 시 회원 id 기반 자리값으로 바꾸고(같은 정보로 재가입 가능),
 * 리뷰 표시용 익명 닉네임은 anonymous_nickname에 둔다.
 */
@Getter
@Entity
@Table(name = "users",   // user는 예약어 충돌 여지가 있어 복수형 사용
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_users_nickname", columnNames = "nickname"),
                @UniqueConstraint(name = "uk_users_email", columnNames = "email"),
                @UniqueConstraint(name = "uk_users_phone", columnNames = "phone")
        })
// Repository 삭제도 soft delete — 리뷰·예약이 user_id 값으로 참조하므로 행을 물리 삭제하지 않는다. 탈퇴(익명화)는 withdraw()로만 한다.
@SQLDelete(sql = "UPDATE users SET deleted = true WHERE id = ?")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseTimeEntity {

    /** 탈퇴 회원의 유니크 컬럼 자리값 — 실제 가입 입력과 겹치지 않는 형식이며, 닉네임 접두어는 온보딩에서 예약어로 거절한다. */
    private static final String WITHDRAWN_NICKNAME_PREFIX = "탈퇴회원#";
    private static final String WITHDRAWN_EMAIL_FORMAT = "withdrawn+%d@hashi.invalid";
    private static final String WITHDRAWN_PHONE_FORMAT = "withdrawn-%d";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nickname", length = 50, nullable = false)
    private String nickname;

    /** 탈퇴 회원의 리뷰 작성자 표시 이름 — 탈퇴 시 배정하며 활성 회원은 null. 여러 탈퇴 회원이 같은 이름을 쓸 수 있다(유니크 아님). */
    @Column(name = "anonymous_nickname", length = 50)
    private String anonymousNickname;

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

    /** 활성 회원이 쓸 수 없는 닉네임 — 탈퇴 자리값 접두어(탈퇴 시 유니크 충돌 방지)와 익명 닉네임 후보(탈퇴 회원과 혼동 방지). */
    public static boolean isReservedNickname(String nickname) {
        return nickname.startsWith(WITHDRAWN_NICKNAME_PREFIX) || AnonymousNickname.isCandidate(nickname);
    }

    /** 온보딩에서 media 소유권 인계·claim이 성공한 뒤 빈 프로필 슬롯에 연결한다. */
    public void assignOnboardingProfileImage(UUID assetId) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        if (profileImageKey != null || profileImageAssetId != null) {
            throw new IllegalStateException("onboarding profile image is already assigned");
        }
        profileImageAssetId = assetId;
    }

    /** migration이 User를 잠근 뒤 호출한다. 기존 회원 정보와 legacy key는 그대로 보존한다. */
    public boolean attachBackfilledProfileImage(String expectedKey, UUID assetId) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        boolean unchangedSource = !deleted && profileImageKey != null
                && profileImageKey.equals(expectedKey) && profileImageAssetId == null;
        if (!unchangedSource) {
            return false;
        }
        profileImageAssetId = assetId;
        return true;
    }

    /**
     * 탈퇴 — soft delete 뒤 유니크 컬럼(닉네임·이메일·연락처)을 자리값으로 바꾸고, 영문 이름을 지우고,
     * 리뷰 표시용 익명 닉네임을 배정하며 프로필 사진 연결을 끊는다(asset 자체의 정리는 호출 측이 MediaPort로 한다).
     * 생년월일은 명세의 익명화 대상 목록에 없고 NOT NULL이라 그대로 둔다(SPRINT-001 §3.3).
     */
    public void withdraw() {
        if (deleted) {
            throw new IllegalStateException("user is already withdrawn");
        }
        this.deleted = true;
        this.nickname = WITHDRAWN_NICKNAME_PREFIX + id;
        this.email = WITHDRAWN_EMAIL_FORMAT.formatted(id);
        this.phone = WITHDRAWN_PHONE_FORMAT.formatted(id);
        this.nameEng = null;
        this.anonymousNickname = AnonymousNickname.assignFor(id);
        this.profileImageKey = null;
        this.profileImageAssetId = null;
    }
}
