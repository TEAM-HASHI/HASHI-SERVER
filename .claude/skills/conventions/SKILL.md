---
name: conventions
description: 이 프로젝트(HASHI · Spring Modulith 모듈러 모놀리스 · Java 21)의 코딩 컨벤션을 적용하는 스킬. 모듈/패키지 생성, 모듈 간 통신, 에러 처리, 인증, 테스트 등 코드를 작성하거나 리뷰할 때 /conventions로 호출하면 docs/conventions/ 문서를 읽고 컨벤션에 맞는 코드를 작성한다.
---

# Convention 적용 워크플로우

코딩 작업 전에 이 워크플로우를 따라 컨벤션을 파악하고 적용한다.

## Step 1 — 인덱스 읽기

반드시 `docs/conventions/00-index.md`를 먼저 읽는다.
인덱스에는 작업 유형별로 참조해야 할 문서가 정리되어 있다.

```
Read: docs/conventions/00-index.md
```

## Step 2 — 필요한 문서만 선택적으로 읽기

현재 작업 컨텍스트를 바탕으로 인덱스의 "언제 참조하는가" 열을 보고 필요한 문서만 읽는다.
모든 문서를 한 번에 읽지 않는다.

| 작업 유형 | 읽을 문서 |
|-----------|-----------|
| 새 모듈·애그리거트 설계, 모듈 간 통신(포트·이벤트), 의존 방향, shared 배치 | `architecture.md` |
| Controller / Service / Repository / DTO / VO 작성 | `coding-style.md` |
| 새 에러·성공 코드 추가, 예외 처리 | `error-handling.md` |
| 인증이 필요한 API, 현재 사용자 조회 | `auth.md` |
| 테스트 작성(모듈 단위·구조 검증) | `testing.md` |

인덱스 하단의 "빠른 참조" 섹션도 활용한다.

## Step 3 — 컨벤션 적용 및 자가 검증

코드 작성 완료 후 아래 항목을 확인한다.

- [ ] `common`/`core`/`util` 등 공통 잡동사니 모듈·패키지를 만들지 않았는가 (`architecture.md` §3)
- [ ] 모듈 경계를 애그리거트 단위로 두고, 모듈 내부 패키지 레이아웃을 지켰는가 (`architecture.md` §1·§2)
- [ ] 모듈 간 호출을 상대의 `internal`/Repository가 아니라 `<Context>Port`로 했는가 (`architecture.md` §5)
- [ ] 모듈 간 순환 의존·FK·조인이 없는가, 의존 방향이 단방향(도메인→shared)인가 (`architecture.md` §5·부록)
- [ ] 공통 코드를 `shared`에 둘 때 도메인 지식이 없는 틀·계약·VO만 넣었는가 (`architecture.md` §4)
- [ ] 도메인 에러·성공 코드를 각 모듈 `code/`에서 shared 인터페이스로 구현했는가 (`error-handling.md`)
- [ ] 인증이 필요한 API에서 `auth.internal`을 import하지 않고 shared로 공개된 `CurrentUserProvider`로 현재 사용자를 조회했는가 (`auth.md`)
- [ ] 모듈 단위 테스트(`@ApplicationModuleTest`)와 구조 검증(`verify()`/ArchUnit)을 작성·통과했는가 (`testing.md`)