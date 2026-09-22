# 카드뉴스 이미지 media 연결

이 문서는 카드뉴스 기능을 구현하는 매거진 도메인 작업(#209, #210)이 media 업로드 계약을
사용할 때의 기준을 정리한다. 이 작업에서 카드뉴스 등록·수정 API와 공개 응답을 새로
구현하는 것은 아니다.

## media 쪽 계약

- 업로드 purpose: `MAGAZINE_CARD_NEWS`
- 허용 actor: `ADMIN`
- v2 활성화 환경에서 파일당 최대 10MiB, 한 요청의 파일 수는 기존 media API처럼 1~10개
- 변환 role: `MAGAZINE_CARD_NEWS`
- WebP quality 90, nominal 후보 폭 432·864·1296, 기본 폭 864
- 3:4 영역에 맞추되 원본 비율을 유지한다. 중앙 crop, 강제 확대와 원본 공개는 하지 않는다.
- 실제 출력 높이는 원본 비율에 따라 달라질 수 있으므로 응답의 width와 height를 함께 사용한다.

v1이 활성화된 동안에는 카드뉴스 purpose를 발급하지 않는다. v2 worker와 Spring이 함께
배포되고 `media_pipeline_config`를 명시적으로 v2로 전환한 뒤에만 발급한다.

업로드 시작 요청은 기존 `POST /api/v1/media/assets`를 그대로 사용한다.

```json
{
  "purpose": "MAGAZINE_CARD_NEWS",
  "files": [{ "contentType": "image/png", "fileSize": 7340032 }]
}
```

응답의 `assetId`로 업로드 완료를 요청한 뒤 상태를 조회한다. 매거진 서비스에서는
`MediaAssetUse(assetId, MediaAssetPurpose.MAGAZINE_CARD_NEWS)`로 연결을 관리하고,
`MediaImageRequest(assetId, MediaImageRole.MAGAZINE_CARD_NEWS)` 목록을 한 번에 조회한다.
카드뉴스 ID와 표시 순서는 매거진 응답에 두고, 이미지 필드는 반환된 `MediaImage`를 사용한다.

DB 변경은 V27이며, 열려 있는 매거진 상세 PR #209의 V26과 번호를 분리했다.
V27이 먼저 적용된 환경에 V26을 뒤늦게 추가하지 않도록 두 PR의 병합·배포 순서를 맞춘다.

## 매거진 도메인 연결

카드뉴스 항목은 매거진 도메인이 `assetId`와 `displayOrder`를 소유한다. media entity나
S3 key를 직접 참조하지 않고 공개 `MediaPort`로 상태와 role별 이미지를 bulk 조회한다.
등록·수정 트랜잭션에서는 다음을 지킨다.

1. 먼저 media API에서 asset을 만들고 presigned PUT으로 원본을 업로드한다.
2. complete 응답이 처리 중이면 카드뉴스를 공개 상태로 연결하지 않는다.
3. `READY` asset만 카드뉴스 항목에 연결하고, 수정·삭제 시 이전 asset claim도 같은
   트랜잭션에서 정리한다.
4. `displayOrder`는 카드뉴스가 관리하며 media는 순서를 저장하지 않는다.

기존 카드뉴스 URL 응답이 이미 사용 중이라면 새 image 객체를 추가하는 방식으로 호환성을
유지한다. 기존 URL 배열의 순서와 새 배열의 순서를 서로 맞춰 해석하지 않으며, 최종 응답
필드는 매거진 PR에서 클라이언트와 합의한 계약을 따른다.

## 컬렉션의 식당 이미지

컬렉션이 `RestaurantImage`를 재사용하는 경우 이미지를 새로 업로드하거나 복제하지 않는다.
컬렉션이 어떤 식당 사진을 선택하는지(대표 사진인지, 현재 목록의 전체 사진인지)는 매거진
도메인에서 확정하고, 확정한 asset ID와 role을 `MediaPort`로 bulk 조회한다. 식당의 legacy
URL을 컬렉션에 다시 저장하지 않아야 식당 이미지 전환 후에도 최적화된 결과를 재사용할 수
있다.

## 확인할 테스트

- 카드뉴스 asset ID와 표시 순서가 등록·수정·조회에서 보존되는지
- 처리 중인 asset이 기존 원본 URL로 조용히 대체되지 않는지
- 카드뉴스 이미지가 crop되지 않고 실제 width·height가 응답과 일치하는지
- 같은 asset을 두 카드뉴스에서 claim할 수 없는지, 삭제·교체 시 이전 claim이 정리되는지
- 컬렉션이 선택한 식당 이미지의 `MediaPort` 조회가 응답 항목 수에 따라 반복 호출되지 않는지
