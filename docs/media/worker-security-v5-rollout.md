# 이미지 변환 worker 보안 업데이트와 v5 전환

## 변경 범위

Sharp 0.35.5와 fast-uri 3.1.9로 알려진 취약점의 수정 버전을 사용한다.
Linux x64 패키지는 libvips 8.18.7과 librsvg 2.63.2를 포함한다.
새 파일 업로드 제한, 이미지 크기, 품질, 자르기 규칙은 바꾸지 않는다.
카드뉴스는 기존대로 전체 표시와 품질 90을 유지한다.

`v5.json`은 v4에서 `specVersion`과 `processorRevision`만 바꾼 규격이다.
v5 digest는 `448810cc6b305d979f0afd931f05e6efc906132fee7320ec575b71f81d46f18c`이다.
기존 v1~v4 파일과 digest는 그대로 보존한다.

규격 번호는 화면 기능의 출시 횟수가 아니라 **같은 저장 주소에 어떤 변환 결과가 들어가는지**를
구분한다. native 변환 라이브러리가 바뀌면 일부 입력의 결과 바이트가 달라질 수 있다.
제한된 표본에서 결과가 같더라도 모든 파일에서 같다고 보장할 수 없으므로 v4 주소에 새 결과를
기록하지 않는다. 새 작업은 `v5/...`에 기록하고 기존 사진은 원래 주소로 계속 조회한다.

## 읽기 지원과 실행 지원

| 구성 요소 | 지원 범위 |
| --- | --- |
| Spring의 규격 읽기·결과 검증·기존 사진 조회 | v1~v5 |
| worker의 규격 파일 읽기 | v1~v5 |
| 이번 worker가 실제 변환할 수 있는 규격 | **v5만** (`sharp-webp-v3`) |

이번 worker는 v1~v4 작업을 받으면 원본 조회·변환·저장 전에 `ContractMismatchError`를 반환한다.
SQS handler는 해당 메시지만 재시도 대상으로 남긴다. 사용자 파일이 잘못된 것으로 처리하거나
성공한 것처럼 메시지를 버리지 않는다. 반복되면 기존 DLQ·알림 절차로 조사한다.
직접 `processImage`를 호출해도 같은 실행 지원 검사를 거친다.

Spring에 v5 파일이 추가됐다는 이유만으로 발급 규격이 바뀌지 않는다.
현재 적용 값은 계속 `media_pipeline_config`에서 읽는다. 이 변경에는 새 migration,
현재 DB 값 변경, AWS 배포 또는 기존 사진 재변환이 없다.

## 개발 환경 배포 전 확인

**이 문서는 실행 순서이며, 배포·전환 완료 기록이 아니다.** 기존 작업을 처리 중인 worker에
이 artifact를 바로 덮어쓰면 안 된다. 배포는 별도 승인 후 아래 순서로 진행한다.
운영 환경에는 이 개발 시험 승인을 적용하지 않는다.

1. 개발 환경임을 확인하고 현재 config의 version·digest·lock version과 배포 artifact를 기록한다.
   신규 발급을 조건부 갱신(CAS)으로 OFF로 바꾼다. OFF가 이미 적용돼 있으면 그대로 유지한다.
   일반 API 외에 backfill·upgrade 작업도 새 job을 발급하지 않는지 확인한다.
2. **기존 worker를 유지한 채** 구 규격의 진행 중 target/currentJobId, 미완료 EPR,
   request/result queue와 각 DLQ를 확인한다. 단순히 SQS의 한 번 측정값이 0이라는 이유로
   완료로 판단하지 않는다. 늦은 재시도·visibility timeout과 메시지 보존 기간도 고려한다.
   남은 작업은 기존 artifact로 끝내거나 승인된 복구 절차로 해결한다. 큐를 비우거나
   실패 메시지를 삭제해서 숫자만 0으로 만들지 않는다.
3. 위 항목이 모두 해소되고 보존 조건을 충족한 뒤 event source를 일시 중지하고 worker를 교체한다.
   같은 v5 manifest를 가진 Spring으로 모든 API·publisher·result consumer를 교체한다.
   원래 config pointer와 issuance OFF는 이 단계에서 유지한다.
4. 검토한 commit과 배포 ZIP SHA-256을 대조하고, 추출한 ZIP에서 `verify-package.mjs` 결과를 확인한다.
   `executableSpecs`가 v5와 위 digest를 가리키는지, 구 규격 실행 거부와 기존 사진 조회가
   유지되는지 확인한다. 새 fleet의 모든 인스턴스가 같은 규격을 지원해야 한다.
5. event source와 result consumer가 준비되면 승인된 시험 시간에만 config를
   `(기존 version, 기존 digest, OFF)`에서 `(5, v5 digest, ON)`으로 **한 번의 CAS**로 바꾼다.
   조건 불일치나 영향 행 수가 1이 아니면 중단한다. old pointer 상태에서 flag만 ON으로
   바꾸면 v5 전용 worker에 구 규격 작업이 들어가므로 그렇게 재개하지 않는다.
6. 시험 이미지의 업로드 → v5 요청 → 변환 → 결과 반영 → READY → CloudFront 조회를 확인한다.
   구 규격으로 이미 공개 중인 사진도 그대로 보이는지 확인한다. 실패하면 발급을 OFF로 바꾸고
   새 메시지·상태를 보존해 원인을 조사한다. 정리·보호 검증 등 기존 상시 활성화 조건을 통과하기
   전에는 시험 성공만으로 상시 ON을 확정하지 않는다.

v5를 활성화한 뒤에는 current version을 v4로 내리지 않는다. v5 job이나 active 데이터가 있다면
v5 manifest/실행 지원이 없는 과거 artifact로 되돌리지 않고 v5를 지원하는 수정본으로 복구한다.
아직 v5가 활성화되지 않았고 새 데이터도 없다면, 기록해 둔 구 artifact로 복구하되 발급 OFF를
유지하고 원래 규격 지원·큐 상태를 다시 확인한다.

관련 규칙: [이미지 계약 6.4·11.2](image-delivery-contract-v1.md),
[개발 인프라 배포 절차](../../infra/media/README.md).
