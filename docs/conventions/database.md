# DB / Migration Convention

HASHI는 DB schema를 Flyway migration으로 관리한다. Hibernate는 schema를 생성하지 않고, 기동 시 현재 entity와 DB schema가 맞는지 `validate`만 수행한다.

## 1. 기본 원칙

- migration 파일은 `src/main/resources/db/migration`에 둔다.
- 파일명은 `V{version}__{lower_snake_case_description}.sql` 형식을 사용한다.
  - 예: `V1__init_current_schema.sql`
  - 예: `V2__create_restaurant_tables.sql`
- 한 번 merge된 migration은 수정하지 않는다. 변경이 필요하면 다음 version migration을 추가한다.
- `baselineOnMigrate=true`를 상시 설정하지 않는다.
- 운영/개발용 업무 데이터의 초기 적재, 테스트용 샘플 데이터, 어드민 계정 비밀번호 같은 민감 데이터는 migration에 넣지 않는다.
- 개발 확인용 샘플 SQL은 Flyway migration과 분리해서 `docs/dev` 등 별도 위치에 둔다.
- 단, 기능의 필수 초기 상태를 구성하는 **환경 독립적이고 비민감한 제어 데이터**는 versioned
  migration에서 최초 생성할 수 있다. 허용 대상, 필요한 이유와 안전한 초기값을 ADR에 명시한다.
  - 예: [`ADR 0001`](../adr/0001-media-module-and-image-pipeline.md)의 `media_pipeline_config`는
    신규 이미지 작업 발급을 비활성화한 상태로 최초 생성한다. 기능 검증 후 활성화는 별도 운영 절차다.
- 최초 생성과 운영 중 변경을 분리한다. 이 예외로 만든 제어 행의 운영값을 서버 재시작·재배포 시
  초기값으로 덮어쓰지 않는다. 시작 시 초기화 코드나 repeatable migration으로 재초기화하지 않으며,
  이후 변경과 누락 복구는 ADR에 명시한 승인된 운영 절차를 따른다.

## 2. 모듈 경계와 FK

- 다른 aggregate의 테이블에는 FK를 걸지 않는다.
- 다른 aggregate는 ID 값으로만 참조한다.
  - 예: `reservation.user_id`
  - 예: `reservation.restaurant_id`
- 같은 aggregate 내부 테이블끼리는 FK를 사용할 수 있다.
- 모듈 간 조회가 필요하면 Repository 직접 접근이나 DB join이 아니라 공개 Port를 사용한다.

## 3. 기존 DB 적용 정책

- 개발 DB를 비워도 되는 상황이면 schema를 비우고 Flyway migration을 처음부터 적용한다.
- 기존 데이터를 살려야 하면 현재 DB schema와 migration이 일치하는지 먼저 확인하고, 일회성 baseline만 검토한다.
- baseline이 필요한 경우에도 설정값을 상시로 남기지 않는다.

## 4. 작성 체크리스트

- entity의 table/column 이름, 길이, nullable, unique 제약과 일치하는지 확인한다.
- 모듈 경계를 넘는 FK가 없는지 확인한다.
- 금지된 업무·샘플·민감 데이터가 migration에 섞이지 않았는지 확인한다.
- 필수 제어 데이터의 최초 생성은 위 허용 조건과 ADR의 안전한 초기값을 따르고, 운영 중 변경된
  값을 재시작·재배포로 덮어쓰지 않는지 확인한다.
- migration 추가 후 빈 schema에서 앱 기동과 `ddl-auto=validate` 통과를 확인한다.
