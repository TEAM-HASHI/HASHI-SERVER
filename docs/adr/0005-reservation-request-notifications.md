# 운영 예약 접수 알림

등록 식당 예약과 어디든 예약을 접수하면 예약과 포인트 처리가 커밋된 뒤 Slack과 Discord에 예약 번호, 유형, 마스킹한 예약자 이름, 식당명, 일본 현지 기준 방문 예정 시각과 인원을 보낸다. 예약자 이름은 발행 전에 마스킹하고 예약 담당자가 정확한 성함을 관리자 화면에서 확인한다. 두 채널의 성공 여부는 Event Publication Registry가 각각 기록한다. 실패한 채널의 전송은 주기적으로 다시 시도한다.

외부 HTTP 호출 동안 DB 트랜잭션과 예약 요청 스레드를 점유하지 않도록 고정된 ID의 `@Async`와 `@TransactionalEventListener`를 사용한다. 알림용 실행기에는 별도 큐와 접속·요청 시간이 정해져 있다. Slack과 Discord의 Incoming Webhook에는 중복 제거 키를 전달할 수 없다. 제공자가 메시지를 수락한 뒤 서버가 응답을 받거나 성공을 기록하기 전에 실패하면 같은 예약 번호의 알림이 다시 올 수 있다.

운영 배포의 `.env.prod`에 지정된 운영 채널의 Slack 및 Discord Incoming Webhook URL을 다음 변수로 등록한다.

- `HASHI_RESERVATION_NOTIFICATIONS_SLACK_WEBHOOK_URL`
- `HASHI_RESERVATION_NOTIFICATIONS_DISCORD_WEBHOOK_URL`
- `HASHI_RESERVATION_NOTIFICATIONS_ENABLED=true`

두 URL을 등록한 뒤 기능을 켠다. URL 형식이 잘못됐거나 비어 있으면 기동 시 제공자 이름으로 경고한다. 그 채널의 알림은 미완료 상태로 남겨 설정을 고친 뒤 재전송한다. 개발·로컬 프로필에서는 이 기능과 재전송기가 등록되지 않는다. 전송 장애를 확인할 때는 로그의 제공자, 예약 번호와 HTTP 상태 또는 예외 유형으로 추적한다.
