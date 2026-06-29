# 인증 / 인가 컨벤션

> `auth`(횡단·shared) 모듈 규칙과 현재 사용자 조회 방법. 인증이 필요한 API 작업 시 참조.
> 구조상 위치·의존 규칙은 `architecture.md` §9와 함께 본다.

---

## 1. 원칙

- **MUST**: `auth`는 `@Modulithic(sharedModules = "org.sopt.hashi.auth")`로 등록한 **횡단 관심사** 모듈이다.
- **MUST**: 인증 **강제**(요청 차단)는 Spring Security **필터 체인**이 담당한다. 도메인 모듈은 인증 로직을 갖지 않는다.
- **MUST NOT**: 도메인 모듈이 `auth`의 `internal`(JWT·OAuth·필터 등)을 import하지 않는다.
- **MUST**: 도메인에서 "현재 로그인 사용자"가 필요하면 **`CurrentUserProvider`** 로 읽는다.
- 의존 방향: `auth → user` (단방향). 도메인 모듈은 `auth.internal`에 의존하지 않으며, 현재 사용자 조회가 필요하면 shared로 공개된 `CurrentUserProvider`만 사용한다(이것이 도메인이 auth에 의존하는 유일한 지점).

---

## 2. 현재 사용자 조회

`auth`가 노출하는 유일한 공개 지점은 `CurrentUserProvider`다.

```java
// auth/CurrentUserProvider.java  (auth가 공개)
public interface CurrentUserProvider {
    Long currentUserId();          // 인증 안 됐으면 예외(UNAUTHORIZED)
    boolean isAuthenticated();
}
```

```java
// 도메인 service — 현재 사용자 사용 예
public ReviewResponse write(CreateReviewRequest req) {
    Long userId = currentUserProvider.currentUserId();   // ✅
    // ...
}
```

- **MUST NOT**: 컨트롤러 파라미터로 `userId`를 받아 **신뢰**하지 않는다(위변조 가능). 항상 `CurrentUserProvider`(또는 `@AuthenticationPrincipal`)에서 얻는다.
- **MUST NOT**: 도메인이 `SecurityContextHolder`를 직접 뒤지지 않는다. `CurrentUserProvider` 뒤로 숨긴다.

---

## 3. auth 모듈 내부 (요약)

> 상세 구현은 auth 모듈 소관. 도메인 작업자는 알 필요 없음(§2만 알면 됨).

```
auth/
├─ CurrentUserProvider          # 유일한 공개 지점
├─ code/  AuthErrorCode
└─ internal/
   ├─ KakaoOAuthClient · UserAuthService          # 유저 카카오 OAuth → JWT
   ├─ SmsVerificationService · SmsClient           # 가입 시 SMS 전화번호 인증
   ├─ Admin · AdminAuthService                     # 어드민 ID/PW 로그인
   ├─ JwtProvider · JwtAuthenticationFilter · SecurityConfig · RefreshTokenStore(Redis)
   └─ AuthController · SignUpController · AdminAuthController
```

- 유저 인증 = 카카오 OAuth, 어드민 인증 = ID/PW. 둘 다 JWT 발급, 권한은 `ROLE_USER` / `ROLE_ADMIN`로 구분.
- 리프레시 토큰은 Redis(`RefreshTokenStore`)에 보관.

---

## 4. 회원가입 흐름 (SMS 인증)

- **MUST**: 전화번호 SMS 인증은 가입 **필수** 조건이다(미완료 시 가입 불가).
- 흐름: 카카오 OAuth 성공 → 가입 이력 없으면 회원가입 폼(프로필 사진·연락처·영문 이름(선택)) → **SMS 전화번호 인증** → `user`가 User 저장.
- **MUST**: 인증 상태 연계는 — `auth`가 SMS 인증을 먼저 완료해 Redis에 표시 → 가입 폼 제출 시 `user`가 **인증 완료 여부를 포트로 확인**한 뒤 User를 저장한다.
- 프로필(연락처·프로필 사진·영문 이름) 저장 책임은 **`user`** 모듈. `auth`는 인증만.

---

## 5. 인가(권한)

- **SHOULD**: 메서드/URL 단위 권한은 Spring Security 설정(`SecurityConfig`, `@PreAuthorize` 등)으로 처리한다.
- **MUST**: 어드민 전용 API는 `ROLE_ADMIN`을 요구한다.
- **MUST**: "본인 리소스만 접근"(내 리뷰·내 예약 등)은 도메인 service에서 `currentUserId()`와 리소스 소유자를 비교해 검증한다.

```java
if (!review.ownedBy(currentUserProvider.currentUserId()))
    throw new BusinessException(CommonErrorCode.FORBIDDEN);
```

---

## 빠른 점검

- [ ] 도메인이 `auth.internal`을 import하지 않는가
- [ ] 현재 사용자를 `CurrentUserProvider`로 얻는가 (요청 파라미터 userId 신뢰 금지)
- [ ] 어드민 API에 `ROLE_ADMIN`을 요구하는가
- [ ] 본인 리소스 접근을 소유자 검증으로 막는가
- [ ] (가입) SMS 인증 완료를 user가 포트로 확인한 뒤 저장하는가