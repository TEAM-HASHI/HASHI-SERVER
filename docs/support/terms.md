# 약관 서버 계약 (#246)

PLAN SPRINT-001 §10, TERMS 기준. 공지 #245 위에 쌓은 별도 기능 PR이다.

## API
- 공개 GET /api/v1/terms : 현재 게시 버전만 TermsType 선언 순서대로 제공. 현재 없는 유형은 제외.
- 공개 GET /api/v1/terms/{id} : 현재 게시 버전만 상세. 초안·보관·미존재는 SUPPORT-403.
- ADMIN GET /api/v1/admin/terms/types : 8개 유형 및 currentTermsId(null이면 미게시).
- ADMIN GET /api/v1/admin/terms?type=...&beforeId=... : 해당 유형의 최신 ID순 20개. DRAFT/CURRENT/ARCHIVED.
- ADMIN GET /api/v1/admin/terms/{id} : 초안과 보관 버전 포함 상세.
- ADMIN POST /api/v1/admin/terms : 초안 작성.
- ADMIN PUT /api/v1/admin/terms/{id} : 초안 전체 교체. 유형 변경 금지.
- ADMIN POST /api/v1/admin/terms/{id}/publication : 게시 즉시 현재로 전환.
- ADMIN DELETE /api/v1/admin/terms/{id} : 초안만 삭제.

입력은 type, title(1~100자), version(ASCII 영숫자로 시작, 영숫자/점/밑줄/하이픈 최대50자),
effectiveDate(1000~9999년 날짜), clauses(1~200개)다.
clause는 heading(1~100자), content(필수 일반 텍스트)이며 합계 최대100,000 UTF-16 code units.
배열 순서가 accordion 순서다. HTML을 해석하지 않고 줄바꿈만 표시한다.
유형별 version은 대소문자를 구분하며 초안까지 중복을 금지한다. 삭제한 초안 버전은 재사용 가능하다.
현재/보관 게시 버전은 수정·삭제·재게시할 수 없다.

## 적용 및 동시성
시행일은 표시 정보다. 미래 날짜라도 게시 요청 성공 즉시 현재이며 예약 게시 기능은 없다.
유형 제어 행을 먼저 잠근 뒤 버전을 작성/수정/삭제/게시한다.
동시 게시가 차례대로 성공해도 최종 현재는 하나다. 직전에 게시한 버전은 즉시 보관될 수 있다.
게시 실패는 pointer와 publishedAt 전체를 rollback한다. 기존 현재를 유지한다.
정책 근거와 제어 데이터 예외는 ADR 0004를 참조한다.

V36은 지도 V31~V34 및 공지 V35를 먼저 develop에 병합·검증한 뒤 적용한다.
선행 migration 병합 전에는 merge NO-GO. outOfOrder/baseline 우회는 사용하지 않는다.
법률 문구/승인, 회원 동의 이력, 재동의, 예약 게시, 프런트, 실제 운영 게시/배포는 별도 범위다.
