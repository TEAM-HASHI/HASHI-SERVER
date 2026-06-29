# 컨벤션 — 인덱스

HASHI(Spring Modulith 모듈러 모놀리스 · Java 21) 프로젝트의 컨벤션을 용도별 문서로 분리했다.
작업 종류에 맞는 문서만 읽으면 된다.

| 문서 | 다루는 내용 | 언제 참조하는가 |
| --- | --- | --- |
| [`architecture.md`](./architecture.md) | 모듈 경계(애그리거트), 패키지 구조, `common`/`core` 금지, `shared`(공유 커널), 모듈 간 통신(포트·이벤트), 트랜잭션, `auth`/`admin`, 의존 방향, 구조 검증 | 새 모듈·도메인 설계, 모듈 간 통신, 의존성 구조 리뷰 |
| [`coding-style.md`](./coding-style.md) | 네이밍, 포맷팅, DI, API 설계, DTO/VO/엔티티, 조건문 리팩토링, Validation | Controller·Service·Repository·DTO 등 실제 코드 작성/리뷰 |
| [`error-handling.md`](./error-handling.md) | `ErrorCode`/`SuccessCode` 계약, `BusinessException`, `GlobalExceptionHandler`, 도메인 코드 배치(각 모듈 `code/`) | 새 에러·성공 코드 추가, 예외 처리 로직 작업 |
| [`auth.md`](./auth.md) | `auth`(횡단·shared) 규칙, `CurrentUserProvider`로 현재 사용자 조회, 인증 흐름 | 인증이 필요한 API 작업 |
| [`testing.md`](./testing.md) | `@ApplicationModuleTest`(모듈 단위), `ApplicationModules.verify()`(구조 검증), ArchUnit 규칙 | 테스트 작성, 모듈 경계·의존 검증 |
| [`git-convention.md`](./git-convention.md) | 커밋(Angular)·브랜치(Git Flow)·이슈/PR 규칙, scope=모듈명, 커밋 단위 | 커밋·브랜치 생성·PR 작성 |

## 빠른 참조

- "새 도메인(애그리거트) 모듈 추가하고 싶어" → `architecture.md` (모듈 경계·패키지 레이아웃·포트) → `coding-style.md` (클래스 작성)
- "모듈 간 통신 어떻게 하지" → `architecture.md` §5 (포트 vs 이벤트 선택 기준)
- "에러 코드 하나 추가해야 해" → `error-handling.md` (없으면 `architecture.md` §7)
- "로그인한 유저 정보로 API 만들어줘" → `auth.md` (현재 사용자 = `CurrentUserProvider`)
- "공통으로 쓸 것 같은데 어디 두지" → `architecture.md` §3·§4 (`common` 금지, `shared`는 도메인 무관 틀만)
- "모듈 테스트/구조 검증 짜야 해" → `testing.md`
- "커밋 메시지/브랜치/PR 어떻게 쓰지" → `git-convention.md`

## 절대 원칙 (모든 작업 공통)

- `common`/`core`/`util` 같은 잡동사니 공통 모듈을 만들지 않는다.
- 모듈 경계는 **애그리거트**(비즈니스 능력 + 트랜잭션 일관성) 기준.
- 모듈 간 호출은 상대의 `internal`/Repository가 아니라 **`<Context>Port`** 로만.
- 모듈 간 **순환 의존·FK·조인 금지**. 타 도메인은 ID로만 참조. 의존 방향은 단방향(도메인 → `shared`).
- `shared`에는 **도메인 지식 없는 틀·계약·VO만** 둔다.

자세한 규칙은 각 문서의 `MUST` / `MUST NOT` 항목을 따른다.