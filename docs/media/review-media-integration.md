# 리뷰 첨부 이미지 media 연동

관련 이슈: #276. 기존 리뷰 수정 #179 이후 서버의 첨부 이미지 연결을 구현한다.
클라이언트 변경, 기존 이미지 backfill, 업로드 기능 활성화, AWS 배포는 포함하지 않는다.

## 작성과 수정

- 작성은 기존 `imageFileKeys` 또는 신규 `imageAssetIds` 중 하나를 사용한다. 둘 다 생략하면
  이미지가 없으며, 두 필드를 함께 보내면 빈 배열이어도 400이다. 최대 10장을 유지한다.
- 신규 작성은 본인 소유 `REVIEW`, `UNBOUND`, `ACTIVE`이며 `PROCESSING` 또는 `READY`인
  asset만 claim한다. private original key는 리뷰 테이블에 저장하지 않는다.
- 수정의 기존 `imageFileKeys`는 전체 교체이며 빈 배열은 전체 제거다. 신규 `images`도 최종
  순서의 전체 목록이다. 둘 중 정확히 한 필드를 전달해야 한다.
- `images` 원소는 `{ "reviewImageId": 501 }` 또는 `{ "imageAssetId": "UUID" }` 중 하나다.
  기존 ID는 해당 리뷰에 속한 것만 유지하며 중복 ID/asset은 거부한다. 신규 asset은 READY여야 한다.
- key 없는 신규 asset 슬롯이 있으면 구버전 `imageFileKeys` 수정은 400으로 거부한다.
  구버전 화면에서 보이지 않는 사진을 본문 수정 중 삭제하는 것을 막기 위해 `images`를 요구한다.
  legacy-only 리뷰의 수정과 빈 배열 제거 의미는 그대로다. key가 함께 남은 backfill 참조는
  기존 key 재전송 시 association과 asset을 보존한다.
- 유지한 사진은 ID와 binding을 보존하고, 추가한 사진만 claim하고 제거한 사진만 retire한다.
  리뷰 잠금, 이미지 변경, media 전이, 별점 통계 갱신은 한 로컬 트랜잭션이다.
- 리뷰 자체의 soft delete는 association과 BOUND를 보존한다. 사진 제거/교체와 구분하며,
  물리 삭제는 수행하지 않는다.

## 조회

리뷰 목록은 `previewImages`에 `REVIEW_PREVIEW`, 내 리뷰 상세는 `images`에 `REVIEW_DETAIL`을
반환한다. 두 배열은 `reviewImageId`, 0부터 시작하는 `displayOrder`, `image`, `legacyUrl`을 가진다.
처리 중, 실패, 조회 불일치에도 슬롯과 순서를 유지한다. `imageCount`는 전체 슬롯 수다.

기존 URL 배열은 표시 가능한 legacy 주소와 READY 파생본 주소만 포함한다. asset이 있으면
원본 key 또는 legacy 주소로 fallback하지 않는다. 내 리뷰 수정 컨텍스트는 기존 필드를 유지하며
`reviewImageId`, `imageAssetId`, `image`를 추가한다. asset 슬롯의 `fileKey`는 노출하지 않는다.

식당 사진 탭의 리뷰 항목도 `REVIEW_DETAIL`을 사용한다. key 없는 asset도 개수와 cursor 대상에
포함하며 DB에서 key/asset을 구분해 중복을 계산한다. 목록/상세는 프로필·식당 썸네일과 함께
bulk 조회하며, 사진 탭은 대표/메뉴의 노출 판단 뒤 선택한 리뷰 페이지만 한 번 더 bulk 조회한다.
item별 media 조회는 하지 않는다.

별도 공개 리뷰 사진 상세 endpoint는 이 작업에 포함하지 않았다. 공개 사진 확인은 기존 사진 탭을
통하며, 전용 endpoint는 별도 요구사항으로 남는다.

## 스키마와 배포 경계

V45는 `review_image.image_asset_id`(nullable, UUID 형식, unique)를 추가하고 `file_key`를 nullable로
변경한다. 두 source가 모두 없거나 key가 공백인 행은 CHECK로 차단한다. 미디어 테이블 FK는 없다.

기존 `UNIQUE(review_id, display_order)`와 0..9 CHECK를 유지한 채 ID를 보존해 순서를 교환하려고
`display_order`는 nullable로 전환한다. 잠긴 리뷰의 이미지 순서를 같은 트랜잭션에서 일시 NULL로
flush한 후 최종 0..9 순서로 복원하여 flush한다. 저장 서비스는 모든 최종 슬롯에 순서를 할당하고,
실패하면 임시 순서와 binding 전이가 함께 롤백된다. DB 단독으로 최종 NULL 커밋을 차단하지는
않으므로 이 테이블의 별도 직접 쓰기는 허용하지 않는다.

이 변경은 소스와 테스트 단계다. 기존 계약대로 상태 응답과 새 수정 요청을 이해하는 클라이언트가
배포되기 전 신규 media write를 활성화하지 않는다. 배포·기능 활성화는 별도 승인과 검증이 필요하다.
