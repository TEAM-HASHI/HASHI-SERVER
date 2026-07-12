package org.sopt.hashi.dev;

import java.util.ArrayList;
import java.util.List;
import org.sopt.hashi.auth.dev.DevTokenRole;
import org.sopt.hashi.auth.dev.DevTokenService;
import org.sopt.hashi.reservation.dev.DevReservationDataGenerator;
import org.sopt.hashi.restaurant.dev.DevRestaurantDataGenerator;
import org.sopt.hashi.review.dev.DevReviewDataGenerator;
import org.sopt.hashi.review.dev.DummyReviewTarget;
import org.sopt.hashi.user.dev.DevUserDataGenerator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 더미 테스트 시나리오 조립 — 식당 1곳, 회원 10명, 회원마다 그 식당의 방문 완료 예약 1건과
 * 리뷰 1건(총 10건씩)을 한 트랜잭션으로 생성한다(부분 생성 방지). 생성 자체는 각 도메인 모듈의
 * dev 생성기가 담당하고, 이 서비스는 식별자만 이어 붙인다.
 */
@Profile({"local", "dev"})
@Service
public class DevDataService {

    private static final int USER_COUNT = 10;

    private final DevRestaurantDataGenerator restaurantGenerator;
    private final DevUserDataGenerator userGenerator;
    private final DevReservationDataGenerator reservationGenerator;
    private final DevReviewDataGenerator reviewGenerator;
    private final DevTokenService devTokenService;

    public DevDataService(DevRestaurantDataGenerator restaurantGenerator,
                          DevUserDataGenerator userGenerator,
                          DevReservationDataGenerator reservationGenerator,
                          DevReviewDataGenerator reviewGenerator,
                          DevTokenService devTokenService) {
        this.restaurantGenerator = restaurantGenerator;
        this.userGenerator = userGenerator;
        this.reservationGenerator = reservationGenerator;
        this.reviewGenerator = reviewGenerator;
        this.devTokenService = devTokenService;
    }

    @Transactional
    public DummyScenarioResponse createScenario() {
        Long restaurantId = restaurantGenerator.createRestaurant();
        List<Long> userIds = userGenerator.createUsers(USER_COUNT);
        List<Long> reservationIds = reservationGenerator.createVisitedReservations(restaurantId, userIds);

        List<DummyReviewTarget> targets = new ArrayList<>();
        for (int i = 0; i < reservationIds.size(); i++) {
            targets.add(new DummyReviewTarget(reservationIds.get(i), userIds.get(i)));
        }
        List<Long> reviewIds = reviewGenerator.createReviews(restaurantId, targets);

        // 첫 번째 더미 유저의 토큰을 함께 내려, 별도 발급 없이 곧장 그 유저로 예약·리뷰 조회를 시험할 수 있게 한다.
        Long sampleUserId = userIds.get(0);
        String accessToken = devTokenService.issue(DevTokenRole.USER, sampleUserId).accessToken();
        return new DummyScenarioResponse(restaurantId, userIds, reservationIds, reviewIds,
                new DummyScenarioResponse.SampleUser(sampleUserId, accessToken));
    }
}
