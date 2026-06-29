# Git 컨벤션

> 커밋(Angular 기반) · 브랜치(Git Flow) · 이슈/PR 규칙. 커밋·브랜치 생성·PR 작성 시 참조.

---

## 1. 커밋 메시지

### 기본 구조 (싱글 라인)

```bash
git commit -m "<type>(<scope>): <subject> (#issue)"
```

- **type**: 커밋 종류 (필수)
- **scope**: 변경 모듈 (선택, 우리 프로젝트는 모듈명)
- **subject**: 간결한 설명 (필수)
- body / footer: 상세·이슈 참조 (선택)

### Type 종류

| Type | 설명 | 예시 |
| --- | --- | --- |
| `feat` | 새 기능 | `feat(auth): 카카오 소셜 로그인 추가` |
| `fix` | 버그 수정 | `fix(user): 회원가입 이메일 중복 검증 오류 수정` |
| `docs` | 문서 | `docs(readme): 설치 방법 업데이트` |
| `refactor` | 리팩토링 | `refactor(reservation): 예약 상태 전이 로직 분리` |
| `perf` | 성능 | `perf(restaurant): 검색 인덱싱 최적화` |
| `test` | 테스트 | `test(point): 차감 단위 테스트 추가` |
| `build` | 빌드·의존성 | `build(deps): spring-modulith 1.2 업데이트` |
| `ci` | CI 설정 | `ci(github): PR 자동화 워크플로우 추가` |
| `chore` | 기타(소스 변경 X) | `chore: .gitignore 업데이트` |
| `revert` | 되돌리기 | `revert: feat(auth) 되돌림` |

### 작성 규칙

- **Subject**: 50자 이내 · 마침표 금지 · 명령문("추가함" → "추가") · 한/영 통일
- **Body**: 무엇을·왜(어떻게 X)
- **Footer**: 이슈 참조 `Closes #123` / `Fixes #456`

### 좋은 예 / 나쁜 예

```
✅ feat(reservation): 포인트 사용 연동 (#55)
✅ fix(auth): JWT 만료 시 무한 리프레시 문제 해결 (#87)
✅ refactor(user): 인증 로직을 auth 모듈로 분리

❌ 수정함        ❌ Update code      ❌ feat: 작업
❌ 버그수정.     ❌ FIX: BUG FIX!!!
```

---

## 2. Scope = 모듈명

| scope | 범위 |
| --- | --- |
| `restaurant` | 식당·메뉴·검색 |
| `review` | 리뷰 |
| `reservation` | 예약·결제상태 |
| `point` | 포인트 |
| `magazine` | 매거진 |
| `user` | 회원·찜 |
| `support` | 문의·공지·약관 |
| `auth` | 인증/인가·가입 |
| `admin` | 어드민(위임) |
| `shared` | 공유 커널(틀·VO) |
| `config` | 설정(root 직속) |

### 커밋 단위 규칙

1. **scope 하나 = 커밋 하나** — 한 모듈 안에서 끝낸다
2. type 하나로 설명되면 적정 — 두 type 필요하면 쪼갠다
3. 각 커밋은 **빌드 + `verify()` 통과** — 모듈 경계 위반 중간 상태 금지
4. 모듈에 걸치면 **의존 방향대로** — 포트(피호출) 먼저, 호출 측 나중
5. **shared 변경은 독립 커밋** — 영향 넓으니 다른 기능에 안 섞음

```
feat(point): 포인트 차감/복원 포트 추가
feat(reservation): 포인트 사용 연동 (#55)
feat(user): 회원 탈퇴 시 UserWithdrawnEvent 발행
feat(review): UserWithdrawnEvent 구독해 리뷰 정리
```

---

## 3. 브랜치 전략 (Git Flow)

| 브랜치 | 역할 | 분기 | 머지 |
| --- | --- | --- | --- |
| `main` | 운영 배포 | - | - |
| `develop` | 개발 통합 | `main` | `main` |
| `feat/*` | 기능 개발 | `develop` | `develop` |
| `release/*` | 배포 준비 | `develop` | `main`, `develop` |
| `hotfix/*` | 긴급 수정 | `main` | `main`, `develop` |
| `docs/*` | 문서·컨벤션 | `develop` | `develop` |
| `init/*` | 초기 세팅(구조·빌드) | `develop` | `develop` |
| `chore/*` | 기타 설정·잡무(소스 변경 X) | `develop` | `develop` |

### 네이밍

```
feat/#{이슈번호}/{기능명}      예: feat/#12/kakao-login
release/{버전}                 예: release/1.0.0
hotfix/#{이슈번호}/{버그명}     예: hotfix/#45/payment-error
docs/#{이슈번호}/{주제}        예: docs/#2/conventions-and-claude-skills
init/#{이슈번호}/{주제}        예: init/#1/project-structure
chore/#{이슈번호}/{주제}       예: chore/#7/gitignore-update
```

- ✅ `feat/#12/kakao-login`  ✅ `hotfix/#56/payment-timeout`  ✅ `docs/#2/conventions-and-claude-skills`
- ❌ `feat/login`(이슈 번호 없음)  ❌ `Feat/Login`(대문자)

### 규칙

- 작업 브랜치(`feat`·`docs`·`init`·`chore`)는 `main` 직접 머지 **금지** (→ `develop`). `release`·`hotfix`만 `main`에 머지(+`develop` 역머지)
- 작업 전 항상 `git pull`
- 한 브랜치 = 하나의 기능
- 머지된 브랜치는 즉시 삭제
- 브랜치 이동 시 `git stash` 활용

---

## 4. 이슈 / PR

### 이슈 제목
`[TYPE] 구현 내용` (예: `[Feat] 카카오 로그인`)

### PR 규칙

- [ ] PR 제목은 커밋 컨벤션 형식 `[Feat] 구현 내용 (#이슈번호)`
- [ ] PR 템플릿 모든 항목 작성
- [ ] 최소 1명 이상 Approve
- [ ] CI 체크(빌드·`verify()`·테스트) 모두 통과 후 머지
- [ ] 머지 후 브랜치 삭제

### PR 본문 항목

작업 내용(What) · 변경 사항(Details) · 스크린샷 · 주의 사항 · 관련 이슈(`Closes #N`) · 체크리스트(빌드/테스트 완료, 리뷰 반영).

> GitHub 이슈/PR 템플릿 파일은 `.github/`에 둔다(`ISSUE_TEMPLATE`, `pull_request_template.md`).