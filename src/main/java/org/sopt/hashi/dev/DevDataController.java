package org.sopt.hashi.dev;

import org.sopt.hashi.shared.error.CommonSuccessCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiSuccess;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 개발용 더미데이터 시나리오 생성 API — local·dev 프로필에서만 존재한다(운영은 404). */
@Profile({"local", "dev"})
@RestController
@RequestMapping("/api/v1/dev")
public class DevDataController {

    private final DevDataService devDataService;
    private final DevDatabaseCleaner devDatabaseCleaner;

    public DevDataController(DevDataService devDataService, DevDatabaseCleaner devDatabaseCleaner) {
        this.devDataService = devDataService;
        this.devDatabaseCleaner = devDatabaseCleaner;
    }

    /**
     * 더미 시나리오 생성 — 호출 1번에 식당 1곳·회원 10명을 넣고, 대표(첫 번째) 회원에게는 예약의
     * 모든 상태 케이스(진행중·확정·취소·방문 완료 × 리뷰 미작성/작성/삭제 × 등록/어디든)를 1건씩,
     * 나머지 회원에게는 방문 완료 예약·리뷰를 1건씩 생성한다. 공개 경로라 토큰 없이 호출할 수 있고,
     * 응답의 sampleUser.accessToken을 Authorize에 붙이면 해당 더미 회원으로 예약·리뷰 API를 곧장
     * 시험할 수 있다(케이스별 예약은 sampleUserReservations 참고).
     */
    @ApiSuccess(value = CommonSuccessCode.class, codes = {"CREATED"})
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/dummies")
    public SuccessResponse<DummyScenarioResponse> createDummies() {
        return SuccessResponse.of(CommonSuccessCode.CREATED, devDataService.createScenario());
    }

    /**
     * DB 데이터 초기화 — 스키마·마이그레이션 이력·어드민 계정은 보존하고 나머지 모든 테이블을 비운다
     * (AUTO_INCREMENT도 1로 리셋). 초기화 후 더미 시나리오를 다시 넣어 깨끗한 상태에서 시험할 수 있다.
     */
    @ApiSuccess(value = CommonSuccessCode.class, codes = {"OK"})
    @DeleteMapping("/data")
    public SuccessResponse<ClearDataResponse> clearData() {
        return SuccessResponse.of(CommonSuccessCode.OK,
                ClearDataResponse.from(devDatabaseCleaner.clearAll()));
    }
}
