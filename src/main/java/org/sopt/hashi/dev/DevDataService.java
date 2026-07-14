package org.sopt.hashi.dev;

import java.util.ArrayList;
import java.util.List;
import org.sopt.hashi.auth.dev.DevTokenRole;
import org.sopt.hashi.auth.dev.DevTokenService;
import org.sopt.hashi.dev.DummyScenarioResponse.SampleReservation;
import org.sopt.hashi.dev.DummyScenarioResponse.SampleUser;
import org.sopt.hashi.reservation.ReservationStatus;
import org.sopt.hashi.reservation.ReservationType;
import org.sopt.hashi.reservation.dev.DevReservationDataGenerator;
import org.sopt.hashi.restaurant.dev.DevRestaurantDataGenerator;
import org.sopt.hashi.review.dev.DevReviewDataGenerator;
import org.sopt.hashi.review.dev.DummyReviewTarget;
import org.sopt.hashi.user.dev.DevUserDataGenerator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 더미 테스트 시나리오 조립 — 식당 1곳·회원 10명을 만들고, 대표(첫 번째) 유저에게는 예약이 가질 수
 * 있는 모든 상태 케이스(유형×상태×리뷰 상태)를 1건씩, 나머지 유저에게는 방문 완료 예약과 리뷰를
 * 1건씩 생성해 식당 리뷰 목록·통계를 채운다. 전 과정을 한 트랜잭션으로 묶어 부분 생성을 방지하고,
 * 생성 자체는 각 도메인 모듈의 dev 생성기가 담당하며 이 서비스는 식별자만 이어 붙인다.
 */
@Profile({"local", "dev"})
@Service
public class DevDataService {

    private static final int USER_COUNT = 10;

    /** 방문 전 상태 — 대표 유저의 STANDARD·ANYWHERE 예약에 공통으로 만드는 케이스. */
    private static final List<ReservationStatus> PRE_VISIT_STATUSES = List.of(
            ReservationStatus.REQUESTED,
            ReservationStatus.CONTACTING,
            ReservationStatus.CONFIRMED,
            ReservationStatus.CANCELED);

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
        Long sampleUserId = userIds.getFirst();

        // 대표 유저 외 나머지 — 방문 완료 예약 + 리뷰 1건씩으로 식당 리뷰 목록·통계를 채운다.
        List<Long> otherUserIds = userIds.subList(1, userIds.size());
        List<Long> reservationIds = new ArrayList<>(
                reservationGenerator.createVisitedReservations(restaurantId, otherUserIds));
        List<DummyReviewTarget> targets = new ArrayList<>();
        for (int i = 0; i < reservationIds.size(); i++) {
            targets.add(new DummyReviewTarget(reservationIds.get(i), otherUserIds.get(i)));
        }
        List<Long> reviewIds = new ArrayList<>(reviewGenerator.createReviews(restaurantId, targets));

        // 대표 유저 — 모든 상태 케이스를 1건씩. 전체 식별자 목록에도 이어 붙인다.
        List<SampleReservation> sampleReservations = createSampleReservations(restaurantId, sampleUserId);
        for (SampleReservation sample : sampleReservations) {
            reservationIds.add(sample.reservationId());
            if (sample.reviewId() != null) {
                reviewIds.add(sample.reviewId());
            }
        }

        // 대표 더미 유저의 토큰을 함께 내려, 별도 발급 없이 곧장 그 유저로 예약·리뷰 조회를 시험할 수 있게 한다.
        String accessToken = devTokenService.issue(DevTokenRole.USER, sampleUserId).accessToken();
        return new DummyScenarioResponse(restaurantId, userIds, reservationIds, reviewIds,
                sampleReservations, new SampleUser(sampleUserId, accessToken));
    }

    /**
     * 대표 유저의 예약 상태 케이스 생성 — STANDARD는 방문 전 4상태와 방문 완료 3종(리뷰 미작성·작성·작성 후
     * 삭제), ANYWHERE는 방문 전 4상태와 방문 완료(리뷰 자체가 불가한 유형) 1건을 만든다.
     */
    private List<SampleReservation> createSampleReservations(Long restaurantId, Long sampleUserId) {
        List<SampleReservation> samples = new ArrayList<>();
        for (ReservationStatus status : PRE_VISIT_STATUSES) {
            Long reservationId = reservationGenerator.createReservation(restaurantId, sampleUserId, status);
            samples.add(standardSample(reservationId, status, null, null));
        }

        Long unreviewedId = reservationGenerator
                .createReservation(restaurantId, sampleUserId, ReservationStatus.VISITED);
        samples.add(standardSample(unreviewedId, ReservationStatus.VISITED, "UNREVIEWED", null));

        Long reviewedId = reservationGenerator
                .createReservation(restaurantId, sampleUserId, ReservationStatus.VISITED);
        Long reviewId = reviewGenerator
                .createReviews(restaurantId, List.of(new DummyReviewTarget(reviewedId, sampleUserId)))
                .getFirst();
        samples.add(standardSample(reviewedId, ReservationStatus.VISITED, "REVIEWED", reviewId));

        Long reviewDeletedId = reservationGenerator
                .createReservation(restaurantId, sampleUserId, ReservationStatus.VISITED);
        Long deletedReviewId = reviewGenerator
                .createDeletedReview(restaurantId, new DummyReviewTarget(reviewDeletedId, sampleUserId));
        samples.add(standardSample(reviewDeletedId, ReservationStatus.VISITED, "DELETED", deletedReviewId));

        for (ReservationStatus status : PRE_VISIT_STATUSES) {
            Long reservationId = reservationGenerator.createAnywhereReservation(sampleUserId, status);
            samples.add(anywhereSample(reservationId, status));
        }
        Long anywhereVisitedId = reservationGenerator
                .createAnywhereReservation(sampleUserId, ReservationStatus.VISITED);
        samples.add(anywhereSample(anywhereVisitedId, ReservationStatus.VISITED));

        return samples;
    }

    private SampleReservation standardSample(Long reservationId, ReservationStatus status,
                                             String reviewState, Long reviewId) {
        return new SampleReservation(reservationId, ReservationType.STANDARD, status, reviewState, reviewId);
    }

    private SampleReservation anywhereSample(Long reservationId, ReservationStatus status) {
        return new SampleReservation(reservationId, ReservationType.ANYWHERE, status, null, null);
    }
}
