package org.sopt.hashi.user.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 회원 Repository. 전역 soft delete 필터가 없으므로 활성 회원만 다루는 조회는 deleted 조건을 명시한다.
 * 탈퇴 회원을 포함해야 하는 조회(리뷰 작성자 표시용 findAllById)만 조건 없이 쓴다.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /** 활성 회원 단건 — 탈퇴 회원은 제외한다. */
    Optional<User> findByIdAndDeletedFalse(Long id);

    /** 탈퇴 전 회원 행을 잠근다 — 조건 검사와 삭제 사이에 다른 탈퇴·변경이 끼어들지 못하게 한다. 탈퇴 회원은 제외한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :userId and u.deleted = false")
    Optional<User> findByIdForUpdate(@Param("userId") Long userId);

    boolean existsByIdAndDeletedFalse(Long id);

    /** [어드민] 활성 회원 목록. */
    Page<User> findByDeletedFalse(Pageable pageable);

    /** [어드민] 닉네임 부분 일치 검색 — 활성 회원만. */
    Page<User> findByNicknameContainingAndDeletedFalse(String nickname, Pageable pageable);

    // 가입 중복 검사 — 유니크 제약은 탈퇴 회원 행에도 걸리므로 deleted 조건 없이 전체를 본다(탈퇴 자리값은 실제 입력과 겹치지 않는다).
    boolean existsByNickname(String nickname);

    boolean existsByEmail(String email);

    boolean existsByPhone(String phone);
}
