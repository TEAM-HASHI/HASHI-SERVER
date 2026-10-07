# 약관 현재 버전의 원자적 전환 (#246)

상태: 구현 계약. PLAN SPRINT-001 §10 및 TERMS 화면 기준.

support_terms_type의 8개 행은 화면 유형과 동시 게시 잠금의 필수 제어 데이터다.
환경 독립·비민감 데이터이며 V37에서 current_version_id=NULL로 최초 생성한다.
본문·법률 문구·개인정보·동의 이력·실제 게시 버전은 migration에 넣지 않는다.
서버 시작/repeatable migration으로 운영 pointer를 재초기화하지 않는다.

모든 쓰기는 유형 행을 PESSIMISTIC_WRITE로 먼저 잠근다. 개별 수정·게시·삭제는 type scalar를 조회한 뒤
잠금 후 버전 엔티티와 중복 키를 locking current read로 다시 로드하므로 잠금 전 entity snapshot을 재사용하지 않는다.
유형 변경은 금지다. 버전 키는 유형별 ASCII 대소문자를 구분하고 DB unique도 적용한다.
게시 시 버전의 publishedAt 저장과 현재 pointer 교체를 같은 transaction에서 수행한다.
기존 pointer를 삭제하지 않으며 새 pointer 실패는 전체 rollback되어 이전 버전을 유지한다.
유형과 pointer 버전의 일치는 내부 composite FK로 보호한다.
게시된 버전의 수정·삭제·재게시를 차단하며 이전 공개 버전은 ARCHIVED로 보관한다.
동시 게시는 잠금 획득 순서에 따라 순차 처리되어 최종 pointer 하나만 남고 두 버전은 모두 보관된다.

시행일은 화면 표시 정보이며 게시 즉시 현재로 지정한다. 미래 시행일도 예약 게시를 발생시키지 않는다.
게시 전 운영자가 시행일과 실제 적용 시점을 검토해야 한다.
예약 게시·법률 문구 작성/승인·회원 동의 이력·재동의·프런트 화면·운영 게시·배포는 별도다.
누락된 유형 제어 행은 fail closed하며 승인된 forward migration으로 복구한다.
