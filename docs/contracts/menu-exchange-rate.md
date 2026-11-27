# 메뉴 원화 예상 가격

일반 식당과 오늘의 식당은 기존 메뉴 목록·상세 API를 공유한다. 엔화 메뉴의 기존 `currency`, `price`와 함께 `wonEstimate`를 내려준다. 금액은 메뉴의 원래 가격 × 100엔당 매매기준율 ÷ 100을 원 단위로 반올림한 값이다. 페이지당 한 번 조회한 환율로 모든 메뉴를 계산한다. 첫 고시값을 확보하지 못했거나 엔화 메뉴가 아니면 `wonEstimate`는 null이다.

```json
{"currency":"JPY","price":1200,"wonEstimate":{"amount":11407,"appliedDate":"2026-07-13","quotedDate":"2026-07-10","krwPer100Jpy":950.55}}
```

수집을 활성화하려면 서버에 `HASHI_EXCHANGE_RATE_ENABLED=true`와 `EXIMBANK_API_KEY`를 설정한다. 기본은 자동 수집 꺼짐이다. 메뉴 조회는 은행 API에 연결하지 않는다. 매일 서울 시간 정오에 당일 조회를 시작한다. 여러 인스턴스와 재시작이 당일 시도 횟수를 공유하며 15분 이상 간격으로 최대 3회 조회한다. 정오에 서버가 중지되어 있었다면 당일 재가동 후 다음 분부터 수집한다.

은행 응답에는 별도 고시일 필드가 없다. 당일 일자로 조회한 정상 JPY(100) 행의 매매기준율만 당일 고시로 반영한다. 값이 없으면 직전 적용값과 원래 고시일을 함께 이월한다. 운영자가 이미 보정한 적용일에는 자동 응답이 뒤늦게 도착해도 값을 유지한다.

- `PUT /api/v1/admin/exchange-rates/jpy/{appliedDate}`의 본문에는 `quotedDate`와 `krwPer100Jpy`를 보낸다. 서버가 인증된 관리자 ID를 기록한다. 미래 적용일과 적용일 이후 고시일은 400이다.
- `GET /api/v1/admin/exchange-rates/jpy/history?size=20`은 최신 변경부터 반환한다. 다음 페이지는 마지막 `id`를 `beforeId`로 전달한다. BANK는 은행 고시, CARRY는 이월, MANUAL은 수동 보정이다.
- Flyway 설정은 `out-of-order=false`다. V46~V51 배포가 완료된 환경에 V52를 적용한다.

[한국수출입은행 환율 정보](https://www.data.go.kr/data/3068846/openapi.do)
