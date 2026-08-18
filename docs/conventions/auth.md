# 인증 / 인가 컨벤션

> `auth`(횡단·shared) 모듈 규칙과 현재 사용자 조회 방법. 인증이 필요한 API 작업 시 참조.
> 구조상 위치·의존 규칙은 `architecture.md` §9와 함께 본다.

---

## 1. 원칙

- **MUST**: `auth`는 `@Modulithic(sharedModules = "auth")`로 등록한 **횡단 관심사** 모듈이다.
- **MUST**: 인증 **강제**(요청 차단)는 Spring Security **필터 체인**이 담당한다. 도메인 모듈은 인증 로직을 갖지 않는다.
- **MUST NOT**: 도메인 모듈이 `auth`의 `internal`(JWT·OAuth·필터 등)을 import하지 않는다.
- **MUST**: 도메인에서 "현재 로그인 사용자"가 필요하면 **`CurrentUserProvider`** 로 읽는다.
- **MUST**: USER, ADMIN, ONBOARDING 유형까지 구분해야 하는 제한된 기능은 **`CurrentActorProvider`** 로 읽는다. 일반 사용자 도메인은 이를 현재 사용자 조회 대용으로 사용하지 않는다.
- 의존 방향: **도메인 → auth** (auth는 depended-upon 위치). auth는 인증이라는 generic subdomain·횡단 모듈로 잘 변하지 않아, 불안정한 도메인이 안정적인 auth로 의존을 모은다(SDP·ADP). 도메인은 `auth.internal`에 의존하지 않고, auth가 **공개한 지점**(`CurrentUserProvider`, `CurrentActorProvider`, `AuthAccountPort`)으로만 auth를 참조한다.
- **MUST**: **`auth`는 어떤 도메인 모듈도 되참조하지 않는다**(순환 방지·안정성 유지). `shared`라서 순환 검사가 면제되는 게 아니라, auth가 도메인을 되참조하지 않아 무순환이 유지된다 — auth가 도메인을 관찰해야 하면 **이벤트**로 붙인다.

---

## 2. 현재 사용자 조회

`auth`가 노출하는 공개 지점은 `CurrentUserProvider`(현재 사용자 조회), `CurrentActorProvider`(역할 단위 actor 조회)와 `AuthAccountPort`(온보딩 계정 연결)다. 일반적인 현재 사용자 조회는 `CurrentUserProvider`를 쓴다.

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

### 2-1. 역할 단위 actor 조회

이미지 업로드처럼 USER, ADMIN, ONBOARDING을 모두 받으면서 유형별 권한과 소유권을
구분해야 하는 기능은 `CurrentActorProvider`를 사용한다.

```java
// auth가 공개할 목표 계약. 실제 타입은 구현 이슈에서 확정한다.
public interface CurrentActorProvider {
    CurrentActor currentActor();
}
```

- **MUST**: actor 유형과 식별자를 함께 비교한다. USER 1과 ADMIN 1을 같은 소유자로 취급하지 않는다.
- **MUST NOT**: ONBOARDING의 내부 subject를 API 응답이나 로그에 노출하지 않는다.
- **MUST NOT**: 요청 DTO의 actor 유형이나 소유자 ID를 신뢰하지 않는다.
- **MUST**: 역할 구분이 필요 없는 사용자 도메인은 기존 `CurrentUserProvider`를 유지한다.

---

## 3. auth 모듈 내부 (요약)

> 상세 구현은 auth 모듈 소관. 도메인 작업자는 알 필요 없음(§2만 알면 됨).

```text
auth/
├─ CurrentUserProvider          # 공개 지점 (현재 사용자 조회)
├─ CurrentActorProvider         # 공개 지점 (역할 단위 actor 조회, media 등 제한된 기능)
├─ AuthAccountPort              # 공개 지점 (온보딩 소셜 계정 연결 — user가 원자적 커밋 위해 호출)
├─ code/  AuthErrorCode · AuthSuccessCode
├─ event/ UserWithdrawnListener  # 탈퇴 이벤트 구독 → 토큰 무효화·블랙리스트
└─ internal/                     # 관심사별 하위 패키지 (경계 넘는 협력자는 public, 모듈 밖엔 여전히 비공개)
   ├─ account/    AuthAccount · AuthAccountRepository · AuthAccountService · AuthProvider · AuthAccountPortImpl
   ├─ jwt/        JwtProvider · JwtProperties · MemberPrincipal · OnboardingPrincipal · AuthRoles
   ├─ token/      RefreshTokenStore(Redis) · OnboardingTokenStore(Redis)   # TokenBlacklist(Redis) 예정
   ├─ kakao/      KakaoOAuthClient · KakaoProperties · KakaoLoginRequest/Response
   ├─ security/   SecurityConfig · JwtAuthenticationFilter · JwtAuthenticationEntryPoint · JwtAccessDeniedHandler · CookieUtil · OriginValidator · CurrentUserProviderImpl
   ├─ onboarding/ OnboardingJwtIssuer(응답 후처리로 정식 JWT 부착)
   ├─ admin/      Admin · AdminRepository · AdminAuthService · AdminAuthController   # 어드민 ID/PW 로그인·로그아웃
   ├─ web/        AuthController
   └─ UserAuthService                              # 로그인·재발급 오케스트레이터
```

> ⚠️ 예정: 가입 SMS 인증(MVP 제외)은 도입 시 관심사 하위 패키지(`sms/`)에 둔다.

- 유저 인증 = 카카오 OAuth, 어드민 인증 = ID/PW. 둘 다 JWT 발급, 권한은 `ROLE_USER` / `ROLE_ADMIN`로 구분.
- **토큰 전달**: 액세스 토큰은 `Authorization: Bearer` **헤더**로, 리프레시 토큰은 **HttpOnly 쿠키**로 내린다.
- **회전(rotation)**: 재발급 시 리프레시 토큰을 갱신(Redis 교체)한다. **폐기된 리프레시 토큰이 재사용되면 해당 사용자 세션 전체를 무효화**한다.
- 리프레시 토큰·온보딩 임시 토큰은 Redis에 **TTL과 함께** 보관한다.
- **토큰 무효화**: 리프레시 토큰은 Redis에서 삭제한다. 액세스 토큰(무상태 JWT)은 삭제할 수 없으므로 **Redis 블랙리스트**에 등록하고(TTL = 토큰 잔여 만료시간), `JwtAuthenticationFilter`가 매 요청 대조해 차단한다.
- **사용자 로그아웃 API는 MVP 제외**(어드민 로그아웃은 별도 존재). 사용자 측 토큰 무효화는 **회원 탈퇴 시에만** 발생한다 — 탈퇴(`DELETE /api/v1/users/me`)는 `user` 소유이며, `user`가 발행한 `UserWithdrawnEvent`를 `auth`가 구독해 (리프레시 삭제 + 액세스 블랙리스트 등록)로 처리한다.

---

## 4. 회원가입 흐름 (온보딩 임시 토큰)

- 흐름: 카카오 OAuth 성공 → 가입 이력 없으면 `auth`가 **온보딩 임시 토큰**(Redis TTL·1회용·온보딩 API에만 유효) 발급 → 온보딩 폼(프로필 사진·연락처·영문 이름(선택)) 제출 → `user`가 User 저장 → 가입 완료 시 임시 토큰 폐기 + 정식 JWT 발급.
- **MUST**: 온보딩 요청은 **임시 토큰으로 인증**되며 auth의 Security 필터가 검증한다. `user`는 필터가 검증한 컨텍스트만 신뢰해 User를 저장하고, **소셜 계정 연결은 `AuthAccountPort`로 호출한다** — 회원 생성과 계정 연결이 **한 트랜잭션에서 원자적으로 커밋**되도록 예외적으로 허용한 `user → auth` 호출이다(그 외 auth 내부는 참조하지 않는다). 연결 대상 제공자·kakaoId는 auth가 온보딩 컨텍스트에서 직접 읽는다.
- **MUST**: 임시 토큰은 정식 JWT와 **권한을 구분**해, 온보딩 외 API에는 접근할 수 없게 한다.
  단 하나의 예외 — **`GET /api/v1/auth/me`**(인증 상태 조회)는 클라 진입 라우팅용으로 온보딩 토큰의 접근을 허용한다. 리소스 접근이 아니며, 응답은 토큰 컨텍스트(`subjectId`·`role`)만 담는다. 단 온보딩 토큰은 subject가 내부 식별자(kakaoId)라 `subjectId`를 `null`로 내린다(USER·ADMIN은 각각 userId·adminId).
- **MUST**: 온보딩 성공 시 **정식 JWT 발급은 `auth`가 담당**한다. `user`는 프로필 저장만 하고, `auth`의 **인터셉터(응답 후처리)**가 저장 성공 응답에 정식 JWT(access 헤더 + refresh 쿠키)를 실어준다 — `user`가 JWT 발급을 위해 `auth`를 호출하지 않는다(순환 방지). → 가입과 동시에 로그인 상태가 된다.
- 프로필(연락처·프로필 사진·영문 이름) 저장 책임은 **`user`** 모듈. `auth`는 인증·임시 토큰·정식 JWT 발급만.
- **⚠️ MVP 제외 — SMS 전화번호 인증**: 원래 가입 필수 조건(카카오 성공 → SMS 인증 → 저장)이었으나 MVP에서 제외. 추후 도입 시, SMS 완료 여부(`auth` 소유)는 `user`가 조회하지 않고 **auth 필터가 검증 결과를 온보딩 컨텍스트에 주입**하는 방식으로 붙인다(도메인이 auth 내부를 뒤지지 않도록 — auth가 도메인을 되참조하지 않는 원칙 유지).

---

## 5. 인가(권한)

- **SHOULD**: 메서드/URL 단위 권한은 Spring Security 설정(`SecurityConfig`, `@PreAuthorize` 등)으로 처리한다.
- **MUST**: 어드민 전용 API는 `ROLE_ADMIN`을 요구한다.
- **MUST**: "본인 리소스만 접근"(내 리뷰·내 예약 등)은 도메인 service에서 `currentUserId()`와 리소스 소유자를 비교해 검증한다.
- **MUST**: **소유자 전용 리소스**의 소유자 검증 실패는 `FORBIDDEN`(403)이 아니라 **도메인 `NOT_FOUND`(404)로 응답**한다 — 403을 주면 그 id의 리소스가 실제로 존재한다는 사실이 노출된다(id 열거 방지). 타인이 접근할 정상 시나리오가 없는 리소스이므로 클라이언트 UX 손실도 없다. **역할 기반 접근 제어**(어드민 전용 API 등 존재가 비밀이 아닌 경우)는 403을 유지한다.

```java
// 소유자 전용 리소스 — 미존재와 타인 소유를 구분하지 않고 존재를 숨긴다(404)
Review review = reviewRepository.findById(reviewId)
        .filter(found -> found.ownedBy(currentUserProvider.currentUserId()))
        .orElseThrow(() -> new BusinessException(ReviewErrorCode.NOT_FOUND));
```

---

## 빠른 점검

- [ ] 도메인이 `auth.internal`을 import하지 않는가
- [ ] 현재 사용자를 `CurrentUserProvider`로 얻는가 (요청 파라미터 userId 신뢰 금지)
- [ ] 역할 단위 소유권이 필요한 기능만 `CurrentActorProvider`를 사용하고 actor 유형과 식별자를 함께 검증하는가
- [ ] 어드민 API에 `ROLE_ADMIN`을 요구하는가
- [ ] 본인 리소스 접근을 소유자 검증으로 막는가 (검증 실패는 403이 아니라 404로 존재를 숨기는가)
- [ ] (가입) 온보딩이 임시 토큰으로 인증되고, 소셜 계정 연결만 `AuthAccountPort`로(원자적 커밋) 하며 그 외 auth 내부는 참조하지 않는가 (SMS 인증은 ⚠️ MVP 제외)
- [ ] `auth`가 어떤 도메인 모듈도 되참조하지 않는가(도메인 관찰이 필요하면 이벤트)
- [ ] 액세스 토큰은 헤더, 리프레시 토큰은 HttpOnly 쿠키로 내리고, 재발급 시 회전하는가
