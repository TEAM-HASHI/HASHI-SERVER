---
name: git
description: 이 프로젝트(HASHI)의 Git 컨벤션을 적용하는 스킬. 커밋 메시지 작성, 브랜치 생성·네이밍, 이슈·PR 작성 시 /git으로 호출하면 docs/conventions/git-convention.md를 읽고 규칙에 맞게 작성한다.
---

# Git 컨벤션 적용 워크플로우

커밋·브랜치·PR 작업 전에 이 워크플로우를 따른다.

## Step 1 — 규칙 문서 읽기

```
Read: docs/conventions/git-convention.md
```

## Step 2 — 작업 유형별 적용

| 작업 | 적용 규칙 (git-convention.md) |
|------|-------------------------------|
| 커밋 메시지 | §1 형식 `<type>(<scope>): <subject> (#issue)` + §2 scope=모듈명, 커밋 단위 |
| 브랜치 생성 | §3 작업 유형별 패턴(`feat`/`docs`/`init`/`chore`/`hotfix`/`release`), 분기·머지 대상 |
| 이슈/PR 작성 | §4 제목 `[TYPE] 내용 (#N)`, PR 체크리스트 |

## Step 3 — 자가 검증

- [ ] 커밋 type이 목록(feat/fix/docs/refactor/perf/test/build/ci/chore/revert)에 있는가
- [ ] scope가 모듈명 1개이고, 한 커밋이 한 모듈 안에서 끝나는가
- [ ] subject가 50자 이내·마침표 없음·명령문인가
- [ ] 각 커밋이 빌드+`verify()` 통과 상태인가 (모듈 경계 위반 중간 상태 금지)
- [ ] 모듈에 걸치면 의존 방향대로(포트 먼저, 호출 측 나중) 커밋을 나눴는가
- [ ] shared 변경을 독립 커밋으로 분리했는가
- [ ] 브랜치명이 작업 유형별 허용 패턴이고 소문자인가 (`feat/#{이슈}/{기능}`, `docs`·`init`·`chore`/#{이슈}/{주제}, `hotfix/#{이슈}/{버그명}`, `release/{버전}`)
- [ ] 머지 대상이 맞는가 — `feat`·`docs`·`init`·`chore` → `develop`, `release`·`hotfix` → `main`·`develop` (작업 브랜치 → `main` 직접 금지)