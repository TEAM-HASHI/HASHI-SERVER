package org.sopt.hashi.user;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;

/**
 * user 모듈의 공개 포트 — 타 도메인(reservation·review 등)이 회원을 참조할 때 쓰는 최소 계약.
 * 의존 모듈은 user 내부(엔티티·Repository·서비스)가 아니라 이 포트에만 코딩한다.
 */
public interface UserPort {

    /** 활성 회원 요약을 조회한다 — 없거나 탈퇴했으면 empty. 호출 측이 fallback을 결정한다(§5-2). */
    Optional<UserInfo> findById(Long userId);

    /**
     * 활성 회원의 users 행을 잠그고 존재 여부를 돌려준다 — 회원 소유 데이터를 새로 만드는 쓰기(예약·리뷰 등)가 저장 전에 호출한다.
     * 호출자의 트랜잭션 안에서만 쓰며 잠금은 그 트랜잭션이 끝날 때까지 유지된다. 모듈 간 FK가 없어 자식 INSERT가 부모 행을
     * 잠그지 않으므로, 이 잠금이 탈퇴(회원 행 잠금 후 조건 검사)와의 순서를 강제한다. 탈퇴 회원이면 false.
     */
    boolean lockActiveUser(Long userId);

    /**
     * 회원 프로필 요약을 한 번에 조회한다. 요청 순서를 유지하고 존재하지 않는 회원은 제외한다.
     * 탈퇴 회원은 익명 닉네임·프로필 이미지 없음으로 포함한다 — 탈퇴 회원의 리뷰도 작성자와 함께 보여주기 위해서다(REVIEW_POLICY).
     */
    List<UserProfileInfo> findProfiles(Collection<Long> userIds);

    /** [어드민] 활성 회원 목록 — offset 페이지네이션. nicknameKeyword가 있으면 닉네임 부분 일치로 검색한다. */
    Page<AdminUserInfo> findPageByAdmin(AdminUserSortType sortType, String nicknameKeyword, int page, int size);
}
