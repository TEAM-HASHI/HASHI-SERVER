package org.sopt.hashi.auth;

import java.util.Map;
import java.util.Set;

/** 관리자 화면의 확인자 표시를 위한 읽기 전용 공개 포트. 현재 ADMIN 인증에서만 사용할 수 있다. */
public interface AdminAccountPort {

    /** 현재 로그인 ID를 한 번에 조회한다. 없는 계정은 결과에서 제외하며, 과거 로그인 ID를 복원하지 않는다. */
    Map<Long, String> findLoginIds(Set<Long> adminIds);
}
