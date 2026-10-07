# 공지사항 서버 계약 (#245)

기획: PLAN SPRINT-001 §9, MYPAGE_NOTICE. 본 PR은 서버 구현이며 프런트 화면·법률 문구·실제 게시·배포는 별도다.

## API
- GET /api/v1/notices?cursor=... : notices, nextCursor, hasNext. 게시일 DESC, ID DESC; 10개.
- GET /api/v1/notices/{id} : 공개 게시 상세. 초안·삭제는 HTTP 404 (SUPPORT-001).
- ADMIN /api/v1/admin/notices : POST 초안 작성, GET ID 내림차순 목록. `page`(기본 0), `size`(기본 20, 최대 100), 응답 `notices/page/size/totalCount/totalPages`.
- ADMIN /{id} : GET 상세, PUT 전체 수정, DELETE soft delete.
- ADMIN /{id}/publication : POST 최초 게시. 재호출은 최초 게시일 유지.

title 1~100자, body 필수(화면에 표시하는 text 합계 최대 10,000 UTF-16 code units; href는 개별 2,000자), imageAssetIds 필수 배열(빈 배열 가능), 최대 10개·중복 금지.
저장하는 JSON은 UTF-8 기준 MEDIUMTEXT 한도(16,777,215 bytes)를 별도로 검사한다.
배포 DB의 `max_allowed_packet`도 이 JSON과 쿼리 부가 데이터를 수용해야 한다. DB 한도가 더 작으면 입력 검증을 통과해도 DB 오류(500)가 발생하므로 배포 전에 확인한다. 이 PR은 서버 설정을 변경하지 않는다.
body는 HTML이 아닌 배열이다. 각 block은 type(PARAGRAPH/BULLET_LIST/ORDERED_LIST)과 items 배열이다.
items의 각 항목은 span 배열이며 span은 text, bold, href(null 허용)를 가진다.
PARAGRAPH는 item 하나만 허용한다. text 줄바꿈은 줄바꿈으로 렌더링하며 text를 innerHTML에 넣지 않는다.
href는 내부 / 경로 또는 host가 있는 http(s)만 허용한다. 내부는 같은 탭, 외부는 새 탭과 noopener/noreferrer를 사용한다.
목록/제목 날짜는 lastModifiedAt을 YYYY.MM.DD로 표현한다. 상세 복귀 스크롤·재시도·뷰어는 클라이언트 책임이다.

공개·관리자 목록은 본문과 이미지 컬렉션을 SQL에서 제외하고 목록에 필요한 메타데이터만 조회한다.
관리자 목록 항목은 `noticeId/title/status/publishedAt/lastModifiedAt/createdAt/updatedAt`이며, `body/images`는 상세에서 조회한다.
관리자 상세 응답의 `createdAt`·`updatedAt`은 JPA Auditing으로 기록한다.
이미지만 추가하거나 순서를 바꾼 경우도 수정 시각을 기록하며, 같은 내용을 다시 저장하면 유지한다.
`publishedAt`은 최초 게시 시각, `lastModifiedAt`은 공개 화면에 표시할 게시 후 수정 시각이다. 초안 수정도 감사 시각에는 남는다.
공개 Response와 관리자 Response를 구분하며 모듈 간에는 NoticeInfo로 전달한다.

## 이미지·원자성
기존 /api/v1/media 업로드 API에서 purpose NOTICE를 사용한다. ADMIN만 허용하며 JPG/PNG/WebP, 각 10 MiB까지다.
READY NOTICE 자산을 저장 시 claim하고 교체·삭제 시 retire한다. 공지 행 잠금, 저장, binding 변경은 같은 transaction이다.
공지 소속·순서는 support가 소유하고 media 엔티티 FK는 없다. 조회는 하나의 bulk MediaPort 호출을 사용한다.
images에는 assetId와 image projection을 순서대로 반환한다. projection이 없거나 READY가 아니면 개별 placeholder를 표시한다.
spec v3 NOTICE_DETAIL은 inside resize로 원본 비율을 유지하고 metadata를 제거한다. v1/v2는 수정하지 않는다.
공지 migration 적용과 새 worker 배포·검증을 마친 후 media config의 현재 spec을 v3/digest로 전환하는 운영 gate가 필요하다.
본 PR은 config 활성화·AWS 호출을 수행하지 않는다. 기존 pipeline 비활성화/v1/v2 환경에서는 NOTICE 발급이 거절된다.

## Migration 적용 순서
develop의 V31(매거진) 이후 지도 V32~V35를 먼저 반영하고 공지 V36 → V36.1, 약관 V37 순서로 적용한다.
V36은 공지와 작성·수정 시각 컬럼을 생성한다. V36.1은 기존 이미지 purpose·role을 보존하면서 NOTICE·NOTICE_DETAIL을 허용한다.
번호 변경 대상은 아직 병합·적용되지 않은 feature migration이다. 이미 적용한 migration의 SQL이나 checksum은 바꾸지 않는다.

배포 전 대상 DB의 flyway 이력을 확인한다. 이전 feature 번호를 적용한 개인 테스트 DB는 별도 이력 확인이 필요하며,
outOfOrder·repair·baseline으로 자동 우회하지 않는다. 선행 지도 migration이 반영되기 전에는 공지 migration을 배포하지 않는다.
