# 공지사항 서버 계약 (#245)

기획: PLAN SPRINT-001 §9, MYPAGE_NOTICE. 본 PR은 서버 구현이며 프런트 화면·법률 문구·실제 게시·배포는 별도다.

## API
- GET /api/v1/notices?cursor=... : notices, nextCursor, hasNext. 게시일 DESC, ID DESC; 10개.
- GET /api/v1/notices/{id} : 공개 게시 상세. 초안·삭제는 HTTP 404 (SUPPORT-400).
- ADMIN /api/v1/admin/notices : POST 초안 작성, GET 최근 ID순 20개(beforeId 사용).
- ADMIN /{id} : GET 상세, PUT 전체 수정, DELETE soft delete.
- ADMIN /{id}/publication : POST 최초 게시. 재호출은 최초 게시일 유지.

title 1~100자, body 필수(텍스트와 href 합계 최대 10,000 UTF-16 code units), imageAssetIds 필수 배열(빈 배열 가능), 최대 10개·중복 금지.
body는 HTML이 아닌 배열이다. 각 block은 type(PARAGRAPH/BULLET_LIST/ORDERED_LIST)과 items 배열이다.
items의 각 항목은 span 배열이며 span은 text, bold, href(null 허용)를 가진다.
PARAGRAPH는 item 하나만 허용한다. text 줄바꿈은 줄바꿈으로 렌더링하며 text를 innerHTML에 넣지 않는다.
href는 내부 / 경로 또는 host가 있는 http(s)만 허용한다. 내부는 같은 탭, 외부는 새 탭과 noopener/noreferrer를 사용한다.
목록/제목 날짜는 lastModifiedAt을 YYYY.MM.DD로 표현한다. 상세 복귀 스크롤·재시도·뷰어는 클라이언트 책임이다.

## 이미지·원자성
기존 /api/v1/media 업로드 API에서 purpose NOTICE를 사용한다. ADMIN만 허용하며 JPG/PNG/WebP, 각 10 MiB까지다.
READY NOTICE 자산을 저장 시 claim하고 교체·삭제 시 retire한다. 공지 행 잠금, 저장, binding 변경은 같은 transaction이다.
공지 소속·순서는 support가 소유하고 media 엔티티 FK는 없다. 조회는 하나의 bulk MediaPort 호출을 사용한다.
images에는 assetId와 image projection을 순서대로 반환한다. projection이 없거나 READY가 아니면 개별 placeholder를 표시한다.
spec v3 NOTICE_DETAIL은 inside resize로 원본 비율을 유지하고 metadata를 제거한다. v1/v2는 수정하지 않는다.
새 worker를 먼저 배포·검증한 후 media config의 현재 spec을 v3/digest로 전환하는 운영 gate가 필요하다.
본 PR은 config 활성화·AWS 호출을 수행하지 않는다. 기존 pipeline 비활성화/v1/v2 환경에서는 NOTICE 발급이 거절된다.

## Migration 적용 순서
develop 053fdb3의 최대 버전은 V30이고 지도 PR #225/#233/#235 및 컬렉션 지도 PR #244의 V31~V34는 미병합이다.
V35는 그 migration들을 develop에 병합·검증한 뒤 적용한다. 먼저 V35를 배포하면 나중의 V31~V34가 누락된다.
outOfOrder/baseline 우회나 기존 migration 수정은 금지다. 이후 약관 V36은 V35 다음에 적용한다.
본 Draft는 선행 PR의 실제 병합 전까지 merge NO-GO다. 운영 DB를 조회하거나 migration을 적용하지 않았다.
