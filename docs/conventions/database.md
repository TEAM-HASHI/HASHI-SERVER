# DB / Migration Convention

HASHI는 DB schema를 Flyway migration으로 관리한다. Hibernate는 schema를 생성하지 않고, 기동 시 현재 entity와 DB schema가 맞는지 `validate`만 수행한다.

## 1. 기본 원칙

- migration 파일은 `src/main/resources/db/migration`에 둔다.
- 파일명은 `V{version}__{lower_snake_case_description}.sql` 형식을 사용한다.
  - 예: `V1__init_current_schema.sql`
  - 예: `V2__create_restaurant_tables.sql`
- 한 번 merge된 migration은 수정하지 않는다. 변경이 필요하면 다음 version migration을 추가한다.
- `baselineOnMigrate=true`를 상시 설정하지 않는다.
- 운영/개발 데이터 seed, 테스트용 샘플 데이터, 어드민 계정 비밀번호 같은 민감 데이터는 migration에 넣지 않는다.
- 개발 확인용 샘플 SQL은 Flyway migration과 분리해서 `docs/dev` 등 별도 위치에 둔다.

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
- 자동 생성/샘플/비밀 데이터가 migration에 섞이지 않았는지 확인한다.
- migration 추가 후 빈 schema에서 앱 기동과 `ddl-auto=validate` 통과를 확인한다.
