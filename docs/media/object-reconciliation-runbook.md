# DB 미참조 이미지 파일 확인과 정리

이 문서는 S3에는 있지만 DB가 안전하게 참조하지 않는 원본 version과 파생본을 확인하는 절차를
다룬다. 코드를 배포하는 것, 후보를 확인하는 것, 실제 version을 삭제하는 것은 서로 다른 단계다.
이 구현은 운영 AWS에 접속하거나 삭제 설정을 활성화하지 않는다.

## 판단 계약

대상은 `media/originals/{assetId}/original`과
`media/renditions/{assetId}/v{specVersion}/{role}/{width}.webp` 형식만 해석한다. 다음 항목은 삭제하지 않는다.

- 경로, UUID, spec, role, width 또는 version ID를 해석할 수 없는 파일
- DB 조회나 S3 목록·삭제가 실패한 파일
- `ACTIVE` asset의 DB `sourceVersionId`와 일치하는 원본 version. S3 current 여부는 사용하지 않는다.
- 아직 `sourceVersionId`가 고정되지 않은 PENDING_UPLOAD·backfill copy 원본
- active spec, 현재 PROCESSING target spec, DB manifest가 하나라도 있는 과거 spec의 모든 파생본
- `lastIssuedSpecVersion`보다 큰, DB가 발급 사실을 증명하지 못하는 파생본
- asset 전체 정리가 진행 중인 `PURGING` 경로
- 마지막 수정 후 7일이 지나지 않은 version

DB에 asset이 없거나 `PURGED` tombstone만 있는 정상 형식의 파일은 7일 뒤 후보가 된다. DB asset이
있는 원본은 canonical version 이외의 version만 후보가 된다. 파생본은 active·current target·과거
manifest에 속하지 않고 `lastIssuedSpecVersion` 이하인 폐기 spec만 후보가 된다. delete marker는 제거할
경우 과거 파일을 다시 current로 만들 수 있어 이 작업의 삭제 후보에서 제외한다.

S3 후보를 읽은 뒤 삭제 직전 짧은 transaction에서 asset row를 잠가 위 조건을 다시 확인한다. S3
목록과 삭제는 DB transaction 밖에서 실행한다. 삭제는 목록에서 관측한 key와 version ID를 모두
명시한다. 목록 직전과 삭제 직전에 대상 bucket의 versioning 상태가 모두 `Enabled`인지 다시 조회하며,
상태가 없거나 `Suspended`이면 fail-closed한다. 따라서 과거 비버전 객체의 리터럴 `null` version도
Enabled 상태에서만 다루고, 중간에 같은 key가 다시 쓰이면 새 write는 별도 immutable version이 된다.
같은 version의 중복 삭제는 같은 결과로 수렴하며, 늦은 worker가 폐기 spec에 다시 쓴 파일은 다음
scan에서 다시 확인한다.

## 설정과 권한

| 환경변수 | 기본값 | 의미 |
| --- | --- | --- |
| `AWS_MEDIA_RECONCILIATION_ENABLED` | `false` | scheduler와 S3 목록 실행 활성화 |
| `AWS_MEDIA_RECONCILIATION_MODE` | `DRY_RUN` | 후보만 집계. `DELETE`일 때만 exact version 삭제 |
| `AWS_MEDIA_RECONCILIATION_ORPHAN_RETENTION` | `7d` | S3 lastModified 기준 유예 기간. 7일 미만은 시작 실패 |
| `AWS_MEDIA_RECONCILIATION_SCAN_INTERVAL` | `6h` | 최초 실행 지연과 실행 요청 간격 |
| `AWS_MEDIA_RECONCILIATION_SCAN_PAGE_SIZE` | `100` | 한 S3 page의 version·marker 합계. 상한 1,000 |
| `AWS_MEDIA_RECONCILIATION_SCAN_MAX_PAGES` | `2` | 한 실행에서 읽는 page 수. 상한 100 |
| `AWS_MEDIA_RECONCILIATION_SCAN_WORK_BUDGET` | `2m` | 새 목록·파일 판단을 시작할 시간 예산. 상한 30분 |
| `AWS_MEDIA_RECONCILIATION_STORAGE_API_TIMEOUT` | `15s` | SDK API 호출 timeout |
| `AWS_MEDIA_RECONCILIATION_STORAGE_ATTEMPT_TIMEOUT` | `5s` | SDK 개별 시도 timeout |
| `AWS_MEDIA_RECONCILIATION_SHUTDOWN_AWAIT` | `20s` | 전용 executor 종료 대기 설정. 상한 1분 |

`enabled=false`에서는 S3를 읽지 않는다. `enabled=true`, `mode=DRY_RUN`은 version 목록을 읽으므로
`CleanupAccessEnabled=true`로 추가된 기존 `SpringApplicationCleanupPolicy`의
`GetBucketVersioning`, `ListBucketVersions` 권한이 필요하다. 이 flag는 삭제 권한도 함께 부여하므로
role의 전체 유효 권한을 검토하고 Spring mode가 `DRY_RUN`인지 별도로 확인한다. 두 bucket 모두 실제로
`Enabled`가 아니면 DRY_RUN도 목록을 시작하지 않는다. `DELETE` 전환은 별도 운영 승인이 필요하다.

원본과 delivery bucket은 서로 다르고 같은 region이어야 한다. 권한은 기존 cleanup policy와 동일하게
`media/originals/*`, `media/renditions/*`의 version 목록과 exact version 삭제로 제한한다. legacy 경로,
bucket 설정, ACL, Object Lock 우회 권한은 사용하지 않는다.

## 단계별 확인

1. 배포 commit, DB, 두 bucket, Spring role을 확인하고 issuance와 두 정리 실행을 비활성으로 배포한다.
2. 운영 담당자가 7일 보존, 실행량, 실패 알림 수신자와 실제 Object Lock·MFA Delete·Deny를 확인한다.
   두 bucket의 versioning이 `Enabled`이고 전파가 끝났음을 실제 계정에서 확인한다.
3. 승인된 dev에서 cleanup IAM만 적용하고 reconciliation을 `enabled=true`, `mode=DRY_RUN`으로 시작한다.
4. 정상 canonical 원본, active·PROCESSING·과거 manifest 파생본이 `protect`인지 확인한다. 후보 key,
   version, asset ID를 로그·지표·PR에 복사하지 않는다.
5. DB 없는 파일, noncanonical 원본, terminal 실패 spec을 제한된 fixture로 확인한다. DB/S3 오류와
   불명확한 경로가 삭제 후보가 아닌지도 확인한다.
6. 실제 삭제 시험을 별도 승인받은 뒤에만 `mode=DELETE`로 전환한다. 부분 실패, 중단, 재실행과
   늦은 파일 생성이 다음 scan에서 수렴하는지 확인한다.
7. dev 결과와 복구 불가한 exact-version 삭제 영향을 검토한 뒤 prod 적용을 별도로 승인한다.

중단하려면 모든 인스턴스를 `AWS_MEDIA_RECONCILIATION_ENABLED=false`로 교체하고 현재 실행 종료를
확인한다. page cursor와 page 내부 위치는 프로세스 메모리에 있으며, 시간 제한 뒤에는 같은 page의
정확히 다음 object부터 이어 간다. 프로세스 재시작 시 prefix 처음부터 재평가하므로 중복 처리는
가능하지만 immutable exact-version 삭제라 안전하게 수렴한다. 처리량·page/time 상한과 meter는
인스턴스별 값이며, 여러 인스턴스의 합산 상한이나 고유 파일 수가 아니다. 개별 삭제 실패는 다음
순환에서 다시 관측된다. 이미 영구 삭제된 version은 설정 rollback으로 복구되지 않는다.

## 지표와 알림

| meter | 고정 tag | 의미 |
| --- | --- | --- |
| `hashi.media.reconciliation.object` | location, mode, outcome | protect, unknown, delete 후보와 실제 deleted 횟수 |
| `hashi.media.reconciliation.failure` | location, reason | DB, S3 목록·삭제, 중단과 내부 오류 분류 |
| `hashi.media.reconciliation.scan.duration` | mode, status | scan 실행 시간과 완료·상한·부분 실패 상태 |
| `hashi.media.reconciliation.dispatch` | outcome=rejected | 실행 중이거나 종료 중인 중복 요청 거부 |

횟수는 고유 파일 수나 절감 byte가 아니다. 아래 PromQL은 기본 6시간 주기의 두 배인 12시간 창을
사용하는 최소 gate다. 실제 meter export 이름은 배포한 Prometheus endpoint에서 먼저 확인한다.

- `sum(increase(hashi_media_reconciliation_failure_total[12h])) > 0`
- `sum(increase(hashi_media_reconciliation_dispatch_total{outcome="rejected"}[12h])) > 0`
- `sum(increase(hashi_media_reconciliation_scan_duration_seconds_count{status="completed"}[12h])) < 1`
- `sum(increase(hashi_media_reconciliation_object_total{outcome="unknown"}[12h])) > 0`

알람은 배포 변경서에 지정된 media 운영 당번에게 전달하고, 수신자가 정해지지 않았거나 test alarm을
수신하지 못하면 `DELETE`로 전환하지 않는다. failure·unknown이면 해당 위치의 실행을 중지하고 DB/S3
권한·versioning을 확인한다. 완료 부재·dispatch 거부이면 인스턴스별 실행 시간과 executor 종료 상태를
확인한 뒤 `DRY_RUN`에서 재시도한다. key, version, asset ID는 알람 annotation이나 metric tag에 넣지 않는다.

관련 기준: [이미지 계약](image-delivery-contract-v1.md),
[asset 전체 정리](asset-cleanup-runbook.md), [AWS 인프라](../../infra/media/README.md).
