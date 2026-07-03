# 인증 / 인가 컨벤션

> `auth`(횡단·shared) 모듈 규칙과 현재 사용자 조회 방법. 인증이 필요한 API 작업 시 참조.
> 구조상 위치·의존 규칙은 `architecture.md` §9와 함께 본다.

---

## 1. 원칙

- **MUST**: `auth`는 `@Modulithic(sharedModules = "org.sopt.hashi.auth")`로 등록한 **횡단 관심사** 모듈이다.
- **MUST**: 인증 **강제**(요청 차단)는 Spring Security **필터 체인**이 담당한다. 도메인 모듈은 인증 로직을 갖지 않는다.
- **MUST NOT**: 도메인 모듈이 `auth`의 `internal`(JWT·OAuth·필터 등)을 import하지 않는다.
- **MUST**: 도메인에서 "현재 로그인 사용자"가 필요하면 **`CurrentUserProvider`** 로 읽는다.
- 의존 방향: `auth → user` (단방향). 도메인 모듈은 `auth.internal`에 의존하지 않으며, 현재 사용자 조회가 필요하면 `auth`가 공개한 `CurrentUserProvider`만 사용한다(이것이 도메인이 auth에 의존하는 유일한 지점).

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
├─ event/ UserWithdrawnListener  # 탈퇴 이벤트 구독 → 토큰 무효화·블랙리스트
└─ internal/
   ├─ KakaoOAuthClient · UserAuthService          # 유저 카카오 OAuth → JWT
   ├─ SmsVerificationService · SmsClient           # 가입 시 SMS 인증 (⚠️ MVP 제외)
   ├─ Admin · AdminAuthService                     # 어드민 ID/PW 로그인
   ├─ JwtProvider · JwtAuthenticationFilter · OnboardingJwtIssuer(인터셉터) · SecurityConfig · RefreshTokenStore(Redis) · OnboardingTokenStore(Redis) · TokenBlacklist(Redis)
   └─ AuthController · AdminAuthController
```

- 유저 인증 = 카카오 OAuth, 어드민 인증 = ID/PW. 둘 다 JWT 발급, 권한은 `ROLE_USER` / `ROLE_ADMIN`로 구분.
- **토큰 전달**: 액세스 토큰은 `Authorization: Bearer` **헤더**로, 리프레시 토큰은 **HttpOnly 쿠키**로 내린다.
- **회전(rotation)**: 재발급 시 리프레시 토큰을 갱신(Redis 교체)한다. **폐기된 리프레시 토큰이 재사용되면 해당 사용자 세션 전체를 무효화**한다.
- 리프레시 토큰·온보딩 임시 토큰은 Redis에 **TTL과 함께** 보관한다.
- **토큰 무효화**: 리프레시 토큰은 Redis에서 삭제한다. 액세스 토큰(무상태 JWT)은 삭제할 수 없으므로 **Redis 블랙리스트**에 등록하고(TTL = 토큰 잔여 만료시간), `JwtAuthenticationFilter`가 매 요청 대조해 차단한다.
- **사용자 로그아웃 API는 MVP 제외**(어드민 로그아웃은 별도 존재). 사용자 측 토큰 무효화는 **회원 탈퇴 시에만** 발생한다 — 탈퇴(`DELETE /users/me`)는 `user` 소유이며, `user`가 발행한 `UserWithdrawnEvent`를 `auth`가 구독해 (리프레시 삭제 + 액세스 블랙리스트 등록)로 처리한다.

---

## 4. 회원가입 흐름 (온보딩 임시 토큰)

- 흐름: 카카오 OAuth 성공 → 가입 이력 없으면 `auth`가 **온보딩 임시 토큰**(Redis TTL·1회용·온보딩 API에만 유효) 발급 → 온보딩 폼(프로필 사진·연락처·영문 이름(선택)) 제출 → `user`가 User 저장 → 가입 완료 시 임시 토큰 폐기 + 정식 JWT 발급.
- **MUST**: 온보딩 요청은 **임시 토큰으로 인증**되며 auth의 Security 필터가 검증한다. `user`는 auth를 **포트로 조회하지 않고**(순환 방지) 필터가 검증한 컨텍스트만 신뢰해 User를 저장한다.
- **MUST**: 임시 토큰은 정식 JWT와 **권한을 구분**해, 온보딩 외 API에는 접근할 수 없게 한다.
- **MUST**: 온보딩 성공 시 **정식 JWT 발급은 `auth`가 담당**한다. `user`는 프로필 저장만 하고, `auth`의 **인터셉터(응답 후처리)**가 저장 성공 응답에 정식 JWT(access 헤더 + refresh 쿠키)를 실어준다 — `user`가 JWT 발급을 위해 `auth`를 호출하지 않는다(순환 방지). → 가입과 동시에 로그인 상태가 된다.
- 프로필(연락처·프로필 사진·영문 이름) 저장 책임은 **`user`** 모듈. `auth`는 인증·임시 토큰·정식 JWT 발급만.
- **⚠️ MVP 제외 — SMS 전화번호 인증**: 원래 가입 필수 조건(카카오 성공 → SMS 인증 → 저장)이었으나 MVP에서 제외. 추후 도입 시, SMS 완료 여부(`auth` 소유)를 `user`가 포트로 조회하면 `user → auth` **순환**이 생기므로, **auth 필터가 검증 결과를 컨텍스트에 주입**하는 방식으로 붙일 것.

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
- [ ] (가입) 온보딩이 임시 토큰으로 인증되고, user가 auth를 포트로 조회하지 않는가 (SMS 인증은 ⚠️ MVP 제외)
- [ ] 액세스 토큰은 헤더, 리프레시 토큰은 HttpOnly 쿠키로 내리고, 재발급 시 회전하는가