# 앱 로그인과 WebView 인증

기존 웹 로그인 경로와 응답은 유지한다. RN은 카카오 SDK의 **accessToken**을 서버에 전달한다. 인가 코드, ID 토큰, 클라이언트가 보낸 회원 번호로 대체하지 않는다.

## 요청 순서

1. RN이 카카오 SDK 로그인을 완료한다. 취소한 경우 서버 로그인 API를 호출하지 않고 기존 화면에 머문다.
2. `POST /api/v1/auth/app/kakao/login`에 `{"kakaoAccessToken":"SDK_ACCESS_TOKEN"}`을 보낸다.
3. 가입 회원이면 아래 `data`를 받는다. 토큰은 앱의 OS 보안 저장소에 보관한다. URL, WebView 메시지, 로그에 넣지 않는다.

```json
{
  "registered": true,
  "tokens": {
    "accessToken": "APP_ACCESS_TOKEN",
    "refreshToken": "APP_REFRESH_TOKEN",
    "accessExpiresIn": 1800,
    "refreshExpiresIn": 1209600
  }
}
```

모든 응답에는 기존 `success`, `code`, `message`, `data` 봉투가 적용된다. 미가입자는 AUTH-201, `{"registered":false,"tokens":null}`이며 앱 가입 연결은 이번 범위에 없다. 기존 웹 가입 화면으로 안내하고 가입 후 앱 로그인을 다시 시도한다.

4. WebView가 암호학적 난수로 verifier를 만든다(43~128자, 영문/숫자/`-._~`). `BASE64URL(SHA256(verifier))`를 패딩 없이 인코딩한 43자 challenge만 RN에 전달한다. verifier는 WebView 메모리에 보관한다.
5. RN은 앱 access 토큰을 Authorization Bearer 헤더에 넣고 `POST /api/v1/auth/webview/code`에 `{"codeChallenge":"S256_CHALLENGE"}`를 보낸다. `data`는 `{"code":"ONE_TIME_CODE","expiresIn":60}`이다.
6. RN은 코드를 신뢰하는 WebView에만 메시지로 전달한다. WebView는 앱과 같은 API 서버에 `POST /api/v1/auth/webview/exchange`, `{"code":"ONE_TIME_CODE","codeVerifier":"VERIFIER"}`를 보낸다. `credentials: include`가 필요하고, WebView 웹 주소가 서버 CORS 허용 목록에 있어야 한다. Origin 없는 요청은 거절한다.
7. 서버는 `refresh_token` 쿠키만 설정한다(HttpOnly, Secure, SameSite=None, Path=/api/v1/auth). 응답 본문이나 Authorization 헤더에는 토큰이 없다. WebView가 기존 `/api/v1/auth/reissue`를 호출해 웹 access 토큰을 받고 로그인 전 화면으로 돌아간다.

WebView의 URL과 메시지 출처는 RN에서 허용 목록으로 확인한다. 외부 링크로 이동한 WebView에는 코드를 전달하지 않는다. 쿠키 저장소는 네이티브 HTTP와 WebView 사이에 자동 공유된다고 가정하지 않는다. **교환 요청을 WebView 안에서 실행**해야 한다. 이번 변경은 서버 계약이며 실제 RN 기기의 쿠키 수신과 화면 복귀는 클라이언트 연동 시험이 필요하다.

## 재발급과 로그아웃

| 경로 | 인증 입력 | 결과 |
| --- | --- | --- |
| `/api/v1/auth/app/reissue` | 본문 `{"refreshToken":"APP_REFRESH_TOKEN"}` | 새 앱 access/refresh 토큰과 만료 시간 |
| `/api/v1/auth/app/logout` | 위와 같은 본문 | 해당 앱과 연결된 WebView의 리프레시 세션 폐기 |
| 기존 `/api/v1/auth/reissue` | 웹 refresh 쿠키 | 기존 Authorization 헤더와 새 refresh 쿠키 |
| 기존 `/api/v1/auth/logout` | 웹 refresh 쿠키 | 해당 웹 세션만 폐기 |

앱 API는 웹 토큰을, 웹 API는 앱 토큰을 받지 않는다. 웹 refresh의 `client`가 없으면 기존 웹 토큰으로 취급한다. `sid`까지 없는 기존 토큰은 기존 Redis 키를 한 번 확인한 후 새 웹 세션으로 옮긴다. 관리자 인증은 기존 별도 키와 재발급 경로를 유지한다.

앱과 웹의 refresh 재발급은 각각 한 요청씩 실행한다(single-flight). 이미 회전한 refresh를 다시 쓰면 해당 세션을 폐기한다. 앱 refresh 재사용이면 연결된 WebView도 폐기한다. 코드 발급은 부모 앱당 최근 한 건만 유효하며, 새 코드 발급이나 앱 refresh 회전 뒤에는 이전 코드를 다시 발급받아야 한다.

로그아웃은 기존 정책대로 **refresh를 폐기**한다. 이미 발급한 access는 최대 30분간 유효하므로 앱/WebView에서 보관한 access도 지우고 보호된 화면을 닫아야 한다. 다만 로그아웃한 앱 access로 새 WebView 코드를 발급하는 것은 서버가 거절한다. 회원 탈퇴는 기존 사용자 차단 목록과 모든 세션 폐기로 access와 refresh를 모두 차단한다.

## 서버 설정과 저장

- `KAKAO_APP_ID`: SDK와 기존 웹이 사용하는 카카오 애플리케이션의 **숫자 앱 ID**. REST API 키가 아니다. 개발/운영에 각각 확인해 주입한다. 미설정 또는 0이면 앱 로그인만 AUTH-012로 거절하고 기존 웹 서버 시작/로그인은 유지한다.
- 카카오 `/v1/user/access_token_info`의 `id`, `app_id`, `expires_in`을 서버에서 확인한다. 외부 요청은 연결 2초, 읽기 5초 제한이며 리다이렉트를 따르지 않는다. 카카오 토큰은 저장하지 않는다.
- Redis는 현재 단일 인스턴스 구성을 사용한다. 회원별 hash에 sid별 토큰 해시와 client, 만료 시간, 부모 sid를 저장한다. 새 외부 저장소, DB 테이블, 라이브러리는 추가하지 않는다. Redis Cluster 전환은 여러 키 Lua의 hash-slot 설계를 별도로 검토해야 한다.
- 세션은 회원당 최대 128개다. 한도에 도달하면 새 로그인을 AUTH-011로 거절하고 기존 세션은 유지한다. 만료된 세션은 다음 접근 때 제거한다. refresh/코드는 SHA-256 해시로 저장하며 일회용 코드의 유효 시간은 60초다.
- 코드 소비, 부모 활성 확인, WebView 세션 생성은 하나의 Redis Lua 실행으로 처리한다. 로그아웃과 교환이 겹쳐도 부모 없는 자식 세션을 남기지 않는다.
- Redis 장애에서는 세션 발급/교환이 실패한다. DB 탈퇴 트랜잭션이 롤백되면 기존 정책대로 사용자 차단 표식은 복구하고, 폐기된 refresh는 복구하지 않는다(재로그인 필요).
- 앱/WebView 인증 응답은 `Cache-Control: no-store`. 요청 본문, 카카오/자체 토큰, verifier, 코드와 Set-Cookie를 수집하는 디버그 로그/APM 설정을 켜지 않는다.

## 배포 확인

기존 웹 API는 경로/응답이 같지만 사용자 refresh 저장 방식이 바뀐다. 새 버전이 발급한 sid 토큰은 구 서버가 알지 못하므로 **구 버전으로 롤백하면 해당 사용자의 재로그인이 필요**하다. 구/신 서버를 장기간 섞어 서비스하지 않는다. 앱 인증은 별도 클라이언트 출시 전 개발 환경에서 SDK 로그인, 코드 교환, 쿠키 수신, 재발급, 취소, 앱 로그아웃을 확인한다.

참고: [카카오 토큰 정보 보기](https://developers.kakao.com/docs/ko/kakaologin/rest-api#req-access-token-info), [PKCE S256](https://www.rfc-editor.org/rfc/rfc7636#section-4.2).
