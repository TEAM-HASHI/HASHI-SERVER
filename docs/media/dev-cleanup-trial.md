# 개발 이미지 정리 시험 준비

새로 만든 시험 파일만 대상으로 권한과 알림을 준비한다. 운영 환경, 기존 사진, 전체 정리 권한은
변경하지 않는다. 두 스크립트는 로컬 JSON만 만들며 AWS·Grafana에 요청하지 않는다.
출력 파일은 실제 bucket, key, version을 담으므로 저장소 밖의 비공개 폴더에 보관하고 커밋하지 않는다.

## 1. 시험 범위

- `CleanupAccessEnabled=false`, backfill 비활성 상태를 유지한다.
- DB에 없는 새 시험 asset 하나의 원본 version 하나와 파생 version 하나만 허용한다.
- 파생본은 실제 시험 fixture인 `v1/profile-avatar/128.webp`만 받는다. 임의 용도나 크기로 범위를 넓히지 않는다.
- 원본과 파생본 모두 S3 `LastModified` 기준 7일이 지나야 한다. 파일 시간을 바꾸거나 기간을 줄이지 않는다.
- `s3:DeleteObjectVersion`만 추가하며 각 statement에 정확한 key, `s3:VersionId`와 소유 계정을 함께 지정한다.
- 추가 권한은 5~60분 뒤 만료한다. 기존 legacy 권한을 없애거나 좁히는 도구가 아니므로
  role의 모든 정책과 bucket policy를 별도로 확인한다. IAM 시뮬레이션만으로 실제 삭제를 검증했다고 보지 않는다.

전체 asset 정리의 `DELETE`는 이 시험에서 켜지 않는다. 다른 후보를 `PURGING`으로 바꾸고 파일 삭제에
실패하게 만들 수 있기 때문이다. object reconciliation은 DB 상태를 바꾸지 않지만 허용되지 않은 다른
후보에서 권한 오류가 날 수 있다. 먼저 전체 `DRY_RUN` 결과를 확인하고, 승인하지 않은 삭제 후보가 있으면
시험을 중단한다. 정상 사진 보호는 7일 이상 된 canonical·BOUND·PROCESSING 표본이 실제로 조회되고
보호되는지 별도로 확인한다. 어린 파일의 age guard 통과나 IAM 거절만으로 이 검증을 대신하지 않는다.

## 2. 임시 권한 파일 준비

운영자가 DEV 계정·region·EC2 role·두 bucket의 소유권과 versioning을 다시 확인한다.
worker와 Spring이 같은 DEV DB·bucket 조합을 사용하는지, 다른 정리 실행이 없는지도 확인한다.
기존 배포 checker로 baseline boundary가 검토된 소스와 같은지 확인하고 실제 IAM 문서를 저장한다.

로컬 config는 다음 필드를 받는다. 값은 예시를 그대로 배포하지 말고 실제 DEV 값으로 채운다.

```json
{
  "environment": "dev",
  "accountId": "123456789012",
  "region": "ap-northeast-2",
  "springRoleName": "hashi-dev-ec2-role",
  "originalBucket": "hashi-dev-media-pipeline-originalimagebucket-example",
  "deliveryBucket": "hashi-dev-delivery-example",
  "validForMinutes": 30
}
```

생성 시 기록한 private manifest는 `asset_id`, `complete=true`,
`content_is_synthetic_text_not_image=true`, `db_absent_after=true`, `objects`를 포함한다.
objects는 original/rendition 각각 하나이며 `location`, `bucket`, `key`, `version_id`,
`verified=true`, `last_modified_utc`를 기록한다. 이 값은 증거 파일의 형식 검사일 뿐이므로 실행 직전
정확한 S3 version을 다시 조회하고 DB 미참조 상태도 다시 확인한다. manifest를 임의 작성해 증거를 대신하지 않는다.

```text
node infra/media/scripts/prepare-dev-cleanup-trial.mjs <config.json> <private-manifest.json> <actual-boundary.json> <new-private-bundle.json>
```

출력에는 정확한 두 version의 `grant`, 기존 ceiling에 같은 제한만 추가한 `trialBoundary`,
`restoreBoundary`, 원래 문서의 canonical SHA-256과 만료 시각이 들어 있다. 도구는 기존 출력 파일을 덮어쓰지 않는다.
권한 적용 직전에 현재 boundary와 출력의 restoreBoundary가 여전히 같은지 재확인한다.
동시에 배포하거나 다른 담당자가 IAM을 바꾸는 동안에는 적용하지 않는다.

실제 적용은 별도 운영 단계다. 변경 전 IAM policy version과 role 연결을 비공개로 보존한 다음
검토한 `trialBoundary`와 exact grant만 DEV role에 적용한다. 일반 배포 검사는 임시 boundary를
정상 baseline과 다르다고 거절한다. 이는 의도한 안전장치이며 검사나 배포 workflow를 우회하지 않는다.

시험이 끝나면 scheduler를 끄고 실행 종료를 확인한다. 임시 inline policy를 제거하고, 현재 boundary가
이번 trialBoundary와 같은 경우에만 저장한 baseline으로 복구한다. 다르면 다른 변경을 덮어쓰지 말고 중단한다.
처음에 기록한 SHA-256과 전체 문서 동등성을 확인하고 기존 배포 checker를 다시 통과시킨다.
만료 조건은 추가 권한 사용을 막지만 IAM 문서를 자동 회수하지 않는다. exact-version 삭제는 영구 삭제이며
권한이나 서버 설정을 되돌려도 파일이 돌아오지 않는다.

## 3. 기존 Grafana로 시험 알림 준비

별도 Alertmanager나 SMTP 서버는 만들지 않는다. 개발 Grafana의 기존 Prometheus datasource
(`prometheus`)와 확인된 팀 알림 경로를 사용한다. 운영 Grafana에는 설치하지 않는다.

시험 동안 app:8080 한 인스턴스만 관측한다. 시작 직전 Prometheus에서 아래 counter의 합과
`process_start_time_seconds`, `up=1`을 조회해 비공개 baseline으로 남긴다. 없는 counter는 0이지만,
스크레이프 오류나 datasource 오류를 0으로 기록하면 안 된다. 실제 export 이름도 확인한다.

| baseline 키 | metric/tag |
| --- | --- |
| cleanupFailure | hashi_media_cleanup_failure_total |
| reconciliationFailure | hashi_media_reconciliation_failure_total |
| unknown | hashi_media_reconciliation_object_total, outcome=unknown |
| candidates | hashi_media_reconciliation_object_total, outcome=delete |
| deleted | hashi_media_reconciliation_object_total, outcome=deleted, mode=delete |
| cleanupCompleted | hashi_media_cleanup_scan_duration_seconds_count, status=completed |
| reconciliationCompleted | hashi_media_reconciliation_scan_duration_seconds_count, status=completed |
| cleanupDispatch | hashi_media_cleanup_dispatch_total, outcome=rejected |
| reconciliationDispatch | hashi_media_reconciliation_dispatch_total, outcome=rejected |

config는 `environment=dev`, 현재 ISO 시각 `baselineAt`, epoch seconds `processStartedAt`,
`validForMinutes`(5~60), `completionGraceMinutes`(1 이상, 전체 기간 미만),
`component`(cleanup 또는 reconciliation), `candidateLimit`(0~100), `deletedLimit`(0~2),
위 키별 숫자를 담은 `baselines`를 받는다. 실제 scan interval과 work budget을 합한 시간보다
완료 유예 시간을 길게 정하고 이 범위에 들어오지 않으면 시험을 시작하지 않는다.
후보 수는 반복 관측 횟수이므로 동일 파일 재순회도 포함한다. 실제 삭제와 후보 수를 합산하지 않는다.

```text
node infra/media/scripts/prepare-dev-cleanup-alerts.mjs <private-alert-config.json> <new-private-grafana-rules.json>
```

Grafana 11 provisioning 형식의 `apiVersion/groups/rules`를 생성하며 기본값은 모두 paused다.
기존 provisioning 디렉터리에 자동 설치하지 않는다. 그룹 전체 형식을 단일-rule REST API에 그대로
POST하지 말고 Grafana의 file provisioning 또는 대응하는 그룹/개별 rule API 형식으로 검토 후 적용한다.
기존 rule, contact point와 notification policy 전체를 덮어쓰지 않는다. 이 시험의 고정 UID 7개가
이미 있으면 소유권과 종료 상태를 먼저 확인한다. 기존 DEV contact의 수신 테스트 후 해당 경로로만
연결하고 이 7개 rule만 unpause한다. 실제 수신을 확인하기 전에는 삭제 시험을 시작하지 않는다.

알림은 실패, unknown, dispatch 거절, 후보·삭제 상한, 완료 부재와 app 재시작/스크레이프 중단을 확인한다.
처음 생긴 counter의 첫 실패도 놓치지 않도록 `increase()` 대신 시작 기준값과 비교한다.
app이 재시작하면 기준값이 무효이므로 알림 확인 후 시험을 중단하고 baseline부터 다시 준비한다.
알림은 자동으로 삭제 실행을 중단하지 않는다. 시험 종료/만료 시 7개 rule을 pause 또는 제거한다.
기간이 지나면 metric 조건은 꺼지지만 datasource 자체 오류는 계속 알릴 수 있다.

## 완료 증거 구분

1. Node 테스트와 promtool 통과: 정책 생성 범위와 알림 식 검증
2. 실제 Grafana 규칙 등록·발송·수신: 알림 경로 검증
3. 실제 Spring reconciliation을 통한 보호·삭제·재실행: 통합 검증

직접 SDK로 version 하나를 삭제한 결과는 3번을 대신하지 않는다. 각 단계의 결과를 따로 남긴다.
전체 asset 정리와 상시 운영 알림, 일반 경로 삭제 권한은 이 시험 준비와 별도 검토 대상이다.
