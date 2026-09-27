# 테스트 컨벤션

> 모듈러 모놀리스 테스트 작성법과 구조 검증. 테스트 작성, 모듈 경계·의존 검증 시 참조.
> "구조를 강제한다"는 규칙은 `architecture.md` §10, 여기서는 **작성 방법**을 다룬다.

---

## 1. 구조 검증 (필수)

모듈 경계·순환 의존을 빌드 단계에서 강제한다.

- **MUST**: `ApplicationModules.verify()` 테스트를 둔다. 경계 위반·순환 의존 시 빌드 실패.

```java
class ModularityTests {
    static final ApplicationModules modules =
            ApplicationModules.of(HashiApplication.class);

    @Test
    void 모듈_경계와_순환의존_규칙을_검증한다() {
        modules.verify();
    }

    @Test  // (선택) 모듈 구조 문서 자동 생성
    void 모듈_구조_문서를_생성한다() {
        new Documenter(modules).writeModulesAsPlantUml().writeModuleCanvases();
    }
}
```

### ArchUnit — shared 보호
- **SHOULD**: `shared`가 도메인 모듈을 의존하면 실패하는 규칙을 둔다(공유 커널이 common으로 퇴화하는 것 방지).

```java
@AnalyzeClasses(packages = "org.sopt.hashi")
class SharedKernelTest {
    @ArchTest
    static final ArchRule shared_는_도메인_모듈을_의존하지_않는다 =
        noClasses().that().resideInAPackage("..shared..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "..restaurant..", "..review..", "..reservation..",
                "..point..", "..magazine..", "..user..", "..support..",
                "..media..");
}
```

### media 구조와 상태 검증

- **MUST**: `media` 모듈 통합 테스트는 `@ApplicationModuleTest`로 격리하고 외부 storage와 queue adapter를 모킹한다.
- **MUST**: Aggregate 상태 전이, 중복 result, 순서가 뒤바뀐 result, active와 target spec 전환,
  sourceVersionId, specVersion 또는 currentJobId가 다른 늦은 결과를 테스트한다.
- **MUST**: 실제 MySQL 전용 migration과 unique 제약은 Testcontainers MySQL로 검증한다. H2 `create-drop` 결과만으로 통과 처리하지 않는다.
- **MUST**: 콘텐츠 모듈의 일반 요청 테스트는 media 구현을 직접 부트스트랩하지 않고
  `MediaPort`를 모킹한다. migration 전용 backfill runner 테스트만 `MediaBackfillPort`를
  모킹하며 Controller와 일반 Service 테스트에서는 이 Port를 사용하지 않는다.
- **MUST**: Java publisher와 Node worker는 같은 request, success result와 failure result JSON
  Schema 또는 golden fixture로 specDigest를 포함한 queue wire 계약을 검증한다.
- **MUST**: Java publisher와 Node worker는 append-only canonical spec manifest와 같은 manifest
  JSON Schema를 사용하고 version, SHA-256 digest와 exact 산출 규격을 동일하게 해석하는지
  검증한다.
- **MUST**: 목록의 asset 수가 늘어도 `MediaPort` bulk 호출과 rendition 조회 query 수가 고정되는지
  query-count 회귀 테스트를 둔다. 응답 item별 media 조회는 실패로 처리한다.

---

## 2. 모듈 단위 테스트

- **MUST**: 모듈 통합 테스트는 `@ApplicationModuleTest`로 **해당 모듈만** 부트스트랩한다(전체 컨텍스트 로딩 금지).
- **MUST**: 다른 모듈은 실제 빈이 아니라 **포트를 모킹**한다(모듈 격리).

```java
@ApplicationModuleTest
class ReviewModuleTest {

    @MockitoBean ReservationPort reservationPort;   // 타 모듈은 포트로 모킹
    @MockitoBean RestaurantPort restaurantPort;
    @MockitoBean PointPort pointPort;
    @MockitoBean UserPort userPort;                 // 작성자 닉네임·프사 enrich

    @Test
    void 본인의_방문완료_예약만_리뷰를_작성한다(Scenario scenario) {
        given(reservationPort.findById(anyLong()))
                .willReturn(new ReservationInfo(/* ownerId=본인, status=VISITED, restaurantId */));
        // when/then ...
    }
}
```

---

## 3. 이벤트 테스트

- **MUST**: 이벤트 발행/구독은 `Scenario` API로 검증한다(발행 → 리스너 처리까지).
- **MUST**: 핸들러 **멱등성**(같은 이벤트 2회 수신 시 결과 동일)을 테스트한다.
- **MUST**: 외부 broker publisher 예외는 고정 listener ID, EPR 미완료 재전송, terminal job의
  no-op 완료와 전용 executor 종료 시 미완료 publication 보존을 통합 테스트한다.

```java
@ApplicationModuleTest
class UserWithdrawTest {
    @Test
    void 탈퇴_시_포인트_계정이_소멸된다(Scenario scenario) {
        scenario.publish(new UserWithdrawnEvent(1L))
                .andWaitForStateChange(() -> pointAccountRepository.existsByUserId(1L), exists -> !exists)
                .andVerify(exists -> assertThat(exists).isFalse());
    }
}
```

---

## 4. 단위 테스트 (도메인/서비스)

- **MUST**: 도메인 규칙(예: 포인트 음수 불가, 예약 상태 전이)은 **순수 단위 테스트**로 검증한다(스프링 컨텍스트 없이).
- **MUST**: VO(`Money`)의 검증·연산 규칙을 단위 테스트로 고정한다.
- **SHOULD**: 트랜잭션 경계가 중요한 흐름(리뷰 작성+포인트 적립이 한 트랜잭션)은 통합 테스트로 롤백까지 확인한다.

```java
@Test
void 포인트는_음수가_될_수_없다() {
    assertThatThrownBy(() -> new Money(BigDecimal.valueOf(-1), KRW))
        .isInstanceOf(IllegalArgumentException.class);
}
```

---

## 5. 네이밍 / 구조

- **MUST**: 테스트 메서드명은 **한국어 행위 서술**(`@DisplayName` 또는 메서드명)로 "무엇을 검증하는지" 드러낸다.
- **SHOULD**: given-when-then 구조를 따른다.
- **MUST**: 테스트는 모듈 패키지 구조를 그대로 미러링한다(`org.sopt.hashi.review...`).

---

## 빠른 점검

- [ ] `ApplicationModules.verify()` 테스트가 있는가
- [ ] (shared) ArchUnit 의존 차단 규칙이 있는가
- [ ] 모듈 테스트가 `@ApplicationModuleTest` + 타 모듈 포트 모킹으로 격리됐는가
- [ ] 이벤트 핸들러의 멱등성을 테스트했는가
- [ ] 외부 broker publisher 예외의 EPR 재전송과 terminal no-op 완료를 테스트했는가
- [ ] 도메인 규칙과 VO를 순수 단위 테스트로 고정했는가
- [ ] media의 상태 경쟁, queue 중복과 MySQL migration을 실제 계약에 맞게 검증했는가
- [ ] media bulk 조회의 query 수가 응답 item 수에 따라 증가하지 않는가
