# 만료·미연결·실패 이미지 정리

이 문서는 asset 전체 정리 기능의 설정과 운영 절차를 다룬다. 코드를 배포하는 것과 실제 파일을
삭제하는 것은 별도 작업이다. 실제 AWS 적용, 보존 기간 변경과 삭제 실행은 운영 승인을 받은 뒤 진행한다.

## 정리 범위

| 대상 | 기본 보존 기간 | 파일 정리 후 DB 처리 |
| --- | --- | --- |
| 일반 업로드 PENDING_UPLOAD·EXPIRED UNBOUND | 24시간 | asset과 rendition row 정리 |
| 일반 업로드 READY UNBOUND | 24시간 | asset과 rendition row 정리 |
| 일반 업로드 FAILED UNBOUND | 7일 | asset과 rendition row 정리 |
| SYSTEM_BACKFILL UNBOUND | 상태별 기간과 7일 중 긴 기간 | FAILED는 실패 기록 유지, 나머지는 row 정리 |
| FAILED BOUND | 7일 | 실패 상태와 콘텐츠의 이미지 슬롯 유지 |

보존 기간은 최초 정리 시작 시점의 `updatedAt`을 기준으로 확인한다. 모든 대상에서 추가로
`uploadExpiresAt + uploadSafetyWindow`가 지나야 한다. 변경 중인 asset은 잠금 안에서 현재 상태와
시각을 다시 확인한다. 단순히 조회 목록에 포함됐다고 삭제하지 않는다.

다음 대상은 이 실행기로 삭제하지 않는다.

- 정상적으로 연결된 BOUND 이미지와 교체 후 보관 중인 RETIRED 이미지
- 최초 변환 또는 재변환 target이 PROCESSING인 이미지
- legacy 경로의 원본 파일, 다른 asset의 파일, 다른 bucket의 파일
- DB에 없는 asset의 파일과 실패한 재변환 spec의 일부 파생본: 별도 object-only reconciliation 대상
- 개인정보 hard delete와 CDN 캐시 삭제: 별도 운영 정책과 승인 절차 대상

전체 정리와 object-only reconciliation, 개발 환경 통합 검증이 끝나기 전에는
`media_pipeline_config.issuance_enabled=false`를 유지한다.

## 권한과 실행 설정

삭제에는 서로 다른 두 설정이 필요하다.

1. SAM의 `CleanupAccessEnabled=true`: 기존 Spring EC2 role에 정리용 S3 권한을 추가한다.
2. Spring의 `AWS_MEDIA_CLEANUP_ENABLED=true`, `AWS_MEDIA_CLEANUP_MODE=DELETE`: 정리 실행을 허용한다.

둘 다 기본적으로 꺼져 있다. `enabled=true`만 설정하면 기본 mode는 `DRY_RUN`이므로 파일을
삭제하지 않는다. DRY_RUN에는 정리용 S3 삭제 권한이 필요하지 않다. 업로드 만료 후 유예 시간은
자동으로 정하지 않으며, 활성화 시 양수 값을 명시하지 않으면 애플리케이션 시작이 실패한다.

`SpringApplicationCleanupPolicy`는 다음 범위만 허용한다.

- 새 original bucket의 `media/originals/*`: prefix 조건을 둔 `ListBucketVersions`,
  해당 경로의 `DeleteObject`와 `DeleteObjectVersion`
- 지정한 delivery bucket의 `media/renditions/*`: 같은 범위의 version 목록과 삭제
- worker role, legacy 경로, bucket 설정·ACL·공개 정책 변경과 Object Lock 보존 우회 권한은 추가하지 않음

실행기는 DB에서 검증한 UUID로 두 asset prefix를 만들며 외부 입력의 bucket/key를 받지 않는다.
IAM은 media 경로를 제한하고, 정상 이미지 보호와 보존 기간은 Spring의 DB 상태 재검증이 담당한다.
IAM만으로 특정 asset의 삭제 가능 상태가 보장되는 것은 아니다.

버전 목록에서 관측한 key와 version ID를 함께 삭제한다. 기존 비버전 delivery 객체의 리터럴
`null` version과 delete marker도 포함한다. 일반 객체와 특정 버전의 삭제 권한은
[AWS DeleteObjects 문서](https://docs.aws.amazon.com/AmazonS3/latest/API/API_DeleteObjects.html)를 따른다.
**특정 version 삭제는 영구 삭제다. S3 versioning을 켜 두었다는 이유로 복구할 수 있다고 가정하지 않는다.**

dev에서는 `MEDIA_DEV_CLEANUP_ACCESS_ENABLED` repository variable을 SAM parameter로 전달한다.
생략하면 `false`를 명시해서 배포한다. prod에서는 GitHub workflow가 AWS에 접근하지 않으며,
별도 운영자가 전체 parameter와 change set을 검토한다. 이 flag를 끄면 이 stack이 추가한 policy만
제거한다. EC2 role의 기존 policy, bucket policy나 다른 grant까지 회수하지는 않는다.

## 설정값

아래 환경변수는 Spring 시작 시 읽는다. 실행 중 환경변수만 바꿔 즉시 반영되는 기능은 없다.

| 환경변수 | 기본값 | 의미 |
| --- | --- | --- |
| `AWS_MEDIA_CLEANUP_ENABLED` | `false` | 정리 scheduler와 실행부 활성화 |
| `AWS_MEDIA_CLEANUP_MODE` | `DRY_RUN` | 조회·재검증만 수행. 실제 삭제는 `DELETE` |
| `AWS_MEDIA_CLEANUP_UPLOAD_SAFETY_WINDOW` | 없음 | 업로드 만료 후 추가 유예 시간. 활성화 전 명시 |
| `AWS_MEDIA_CLEANUP_RETRY_INTERVAL` | `15m` | PURGING 작업의 재시도 가능 간격 |
| `AWS_MEDIA_CLEANUP_SCAN_INTERVAL` | `30m` | 최초 실행 지연 및 실행 요청 간격 |
| `AWS_MEDIA_CLEANUP_SCAN_BATCH_SIZE` | `25` | 한 batch의 최대 asset 수. 상한 1,000 |
| `AWS_MEDIA_CLEANUP_SCAN_MAX_BATCHES` | `2` | 한 실행의 비어 있지 않은 batch 수. 상한 100 |
| `AWS_MEDIA_CLEANUP_STORAGE_PAGE_SIZE` | `1000` | S3 목록 한 페이지의 object/version 수. 상한 1,000 |
| `AWS_MEDIA_CLEANUP_STORAGE_MAX_PAGES` | `10` | asset의 각 prefix에서 조회할 최대 페이지 수. 상한 100 |
| `AWS_MEDIA_CLEANUP_STORAGE_API_TIMEOUT` | `15s` | SDK API 호출 timeout |
| `AWS_MEDIA_CLEANUP_STORAGE_ATTEMPT_TIMEOUT` | `5s` | SDK 개별 시도 timeout. API timeout 이하 |
| `AWS_MEDIA_CLEANUP_SCAN_WORK_BUDGET` | `2m` | 새 batch/asset 작업을 시작할 시간 예산. 상한 30분 |
| `AWS_MEDIA_CLEANUP_STORAGE_WORK_BUDGET` | `1m` | 한 asset의 두 prefix가 공유하는 S3 시간 예산. 상한 5분 |
| `AWS_MEDIA_CLEANUP_SHUTDOWN_AWAIT` | `20s` | executor 종료 대기 설정. 상한 1분 |

보존 기간은 기존 `AWS_MEDIA_PENDING_RETENTION=24h`,
`AWS_MEDIA_DIRECT_UNBOUND_READY_RETENTION=24h`, `AWS_MEDIA_BACKFILL_UNBOUND_RETENTION=7d`,
`AWS_MEDIA_FAILED_RETENTION=7d`를 사용한다. 승인 없이 값을 줄이지 않는다.

- 기본 처리량은 **프로세스 하나당 한 실행에서 최대 50개 asset**이다. 범주마다 50개가 아니며,
  여러 서버 전체를 합친 상한도 아니다. 서버 수를 늘리면 합산 요청량도 따로 검토한다.
- 전용 executor는 한 thread, 대기열 0으로 동작한다. 실행 중 새 요청은 쌓지 않고 다음 주기에 다시 시도한다.
- 정리 범주를 순환하며 keyset cursor를 유지한다. cursor는 프로세스 재시작 시 초기화되지만,
  PURGING 상태와 재시도 시각은 DB에 남는다. 한 실패 대상이 나머지 범주를 계속 막지 않도록 한다.
- 시간 예산은 새 작업 시작을 제한하며 이미 진행 중인 DB/S3 호출을 강제로 끊지 않는다.
  실제 실행 시간은 예산보다 길 수 있다. Spring lifecycle의 graceful phase 대기는 executor 종료
  대기와 별도이므로, 서버가 반드시 20초 안에 종료된다는 뜻도 아니다.

## 최초 확인과 활성화

1. 배포할 commit, 대상 환경, 두 bucket의 실제 범위와 EC2 role을 확인한다. original과 delivery는
   서로 다른 bucket이어야 한다. 테스트 결과만으로 실제 IAM·S3 동작이 검증됐다고 처리하지 않는다.
2. migration과 Spring을 기본 비활성 상태로 배포한다. `CleanupAccessEnabled=false`,
   `AWS_MEDIA_CLEANUP_ENABLED=false`, `issuance_enabled=false`를 확인한다.
3. 운영 담당자가 보존 기간, 업로드 만료 후 유예 시간, 처리량, 실패 알림 수신자를 확인한다.
   Object Lock, MFA Delete, 명시적 Deny 등 실제 bucket 조건도 확인하며 우회 권한을 추가하지 않는다.
4. 승인된 환경에서 Spring만 `enabled=true`, `mode=DRY_RUN`으로 배포한다. S3 권한 flag는 끈 채
   한 번 이상의 scan을 확인한다. DRY_RUN은 DB 상태 변경, purgeToken 생성, S3 조회·삭제를 하지 않는다.
   따라서 S3 접근 권한과 파일 개수·용량을 검증한 결과로 사용하지 않는다.
5. 제한된 개발 데이터에서 정상 BOUND·RETIRED·PROCESSING이 제외되는지, 만료·실패 조건이
   맞는지 확인한다. 대상 목록·key·token·개인정보를 PR, 로그나 지표 tag에 복사하지 않는다.
6. 실제 삭제 시험을 별도로 승인받은 뒤 정리 IAM을 적용하고 Spring을 `mode=DELETE`로 배포한다.
   원본 version과 파생본을 함께 확인하고, 도중 중단·부분 실패·재개와 FAILED 슬롯 보존도 검증한다.
7. dev 결과를 확인한 뒤 prod 적용은 별도 승인한다. 이 문서는 실제 실행 승인이나 완료 기록이 아니다.

정리 공개 API나 임의 SQL 실행기는 제공하지 않는다. 최초 실행을 빨리 확인하려면 승인된 dev에서
scan interval을 조정하고 재배포한다. 운영 DB 상태나 timestamp를 바꿔 강제로 대상에 넣지 않는다.

## 중단과 재시도

정리는 짧은 DB transaction에서 `ACTIVE → PURGING`과 token을 기록한 뒤 시작한다. S3 작업은
transaction 밖에서 수행하며, 두 prefix가 비었음을 확인해야 DB의 row 삭제 또는 PURGED 기록으로
마무리한다. HTTP 200 응답 안에 개별 삭제 오류가 있어도 완료로 처리하지 않는다.

- 중단하려면 모든 정리 실행 인스턴스를 `AWS_MEDIA_CLEANUP_ENABLED=false`로 교체한다.
  현재 실행의 종료 여부도 확인한다. `issuance_enabled=false`나 worker event source 중지만으로는
  정리가 멈추지 않는다. 필요하면 정리 IAM flag도 별도 변경으로 끈다.
- IAM 변경의 전파와 진행 중인 호출 때문에 권한 회수만으로 즉시 중단됐다고 단정하지 않는다.
  이미 삭제된 version은 설정 rollback으로 돌아오지 않는다.
- 부분 삭제, API 오류, 시간·페이지 상한이나 프로세스 중단 시 PURGING 기록을 남긴다.
  다음 허용 실행은 같은 token으로 남은 version을 확인하고 재개한다. 기본 재시도 간격 15분은
  실행 시각의 보장이 아니며, 실제 재시도는 다음 scan에서 이루어진다.
- 재개에서는 이미 정리 중으로 커밋한 대상에 새 보존 기간을 다시 적용하지 않는다. 기간을 늘려도
  기존 PURGING 작업이 취소되는 것은 아니다. 중지 후 상태와 삭제 여부를 별도로 조사한다.
- `PURGING → ACTIVE`, token 교체나 asset row 선삭제로 복구하지 않는다. 같은 token의 재개와
  정상 종료 경로를 사용한다. FAILED tombstone의 운영 삭제·동일 source 재발급은 이 자동화에 없다.
- 오래된 바이너리로 되돌리기 전에 PURGING 보호 계약과 migration 호환성을 확인한다.
  상태 보호가 없는 이전 버전으로 rollback하지 않는다.

늦은 worker가 폐기 파일을 다시 쓰거나 DB에 없는 파일이 남는 문제는 별도 object-only
reconciliation이 수렴시킨다. 실행 절차는
[object reconciliation runbook](object-reconciliation-runbook.md)을 따른다. 전체 asset 정리만으로
이 문제가 해결됐다고 보고하지 않는다.

## 지표와 알림

아래는 Micrometer의 meter 이름이며 Prometheus에서는 점이 밑줄로 변환되고 counter는 `_total`,
timer는 `_count`·`_sum` 등이 붙는다. 실제 scrape 결과와 alert rule을 dev에서 확인한다.

| meter | 고정 tag | 해석 |
| --- | --- | --- |
| `hashi.media.cleanup.attempt` | mode, stage, outcome | initial/resume 실행 결과. `would_purge`, `purged`, `already_purged`, `skipped`, `incomplete` 구분 |
| `hashi.media.cleanup.failure` | mode, stage, reason | storage unavailable, 응답 오류, 부분 실패, 중단, 내부 오류 분류 |
| `hashi.media.cleanup.scan.duration` | mode, status | 실행 시간과 실행 횟수. 부분 실패·재시도 대기·상한 도달도 구분 |
| `hashi.media.cleanup.dispatch` | outcome=rejected | 실행 중이거나 종료 중이라 새 scan을 받지 못한 횟수 |

`would_purge`는 시도 횟수여서 다음 scan에서 같은 asset이 다시 포함될 수 있다. 고유 대상 수,
삭제된 object 수나 절감 bytes로 표시하지 않는다. `purged`도 asset 정리 완료 횟수이며 S3 파일 개수는 아니다.
기존 `hashi.media.cleanup.candidates`는 보존 기간 기준의 넓은 후보 gauge다. 보호 상태·업로드 유예
검증까지 끝낸 정확한 대상 수가 아니며 FAILED 집계에는 RETIRED도 포함될 수 있다.

삭제 실패 증가, 여러 scan 동안 남은 PURGING, 정상 시각 이후에도 scan 완료 지표가 없는 경우를
알림 대상으로 설정한다. 예산 상한 도달은 곧바로 장애가 아니지만 backlog가 계속 늘면 처리량과
호출 시간을 확인한다. 알림 수신과 실제 AWS 검증은 활성화 전 확인 항목이며 이 PR이 수행하지 않는다.

관련 기준: [이미지 계약](image-delivery-contract-v1.md), [ADR](../adr/0001-media-module-and-image-pipeline.md),
[AWS 인프라](../../infra/media/README.md), [legacy backfill](legacy-backfill-runbook.md).
