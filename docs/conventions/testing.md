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
    void verifiesModuleStructure() {
        modules.verify();
    }

    @Test  // (선택) 모듈 구조 문서 자동 생성
    void writesDocumentation() {
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
                "..point..", "..magazine..", "..user..", "..support..");
}
```

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

    @Test
    void 방문완료자만_리뷰를_작성한다(Scenario scenario) {
        given(reservationPort.hasCompletedVisit(anyLong(), anyLong())).willReturn(true);
        // when/then ...
    }
}
```

---

## 3. 이벤트 테스트

- **MUST**: 이벤트 발행/구독은 `Scenario` API로 검증한다(발행 → 리스너 처리까지).
- **MUST**: 핸들러 **멱등성**(같은 이벤트 2회 수신 시 결과 동일)을 테스트한다.

```java
@ApplicationModuleTest
class UserWithdrawTest {
    @Test
    void 탈퇴_시_리뷰가_정리된다(Scenario scenario) {
        scenario.publish(new UserWithdrawnEvent(1L))
                .andWaitForStateChange(() -> reviewRepository.existsByUserId(1L), exists -> !exists)
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
- [ ] 도메인 규칙·VO를 순수 단위 테스트로 고정했는가