package org.sopt.hashi.magazine.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MagazineMetaRepository extends JpaRepository<MagazineMeta, Long> {

    /** 좋아요 수만 읽는다 — 상세·좋아요 응답용. 리액션과 같은 트랜잭션에서 갱신되므로 커밋된 값은 항상 실제 ACTIVE 수와 같다. */
    @Query("select m.likeCount from MagazineMeta m where m.magazineId = :magazineId")
    Optional<Long> findLikeCountById(@Param("magazineId") Long magazineId);

    /** 메타 행이 없으면 0. */
    default long findLikeCountOrZero(Long magazineId) {
        return findLikeCountById(magazineId).orElse(0L);
    }

    /**
     * 좋아요 수 원자 증가 — 낙관적 락 대신 단일 UPDATE로 동시 갱신을 흡수한다(리뷰 통계와 같은 방식).
     * 같은 매거진에 요청이 몰리면 이 행의 락을 잠깐 기다리지만, 리액션 저장과 같은 트랜잭션이라 둘이 어긋날 길이 없다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update meta_magazine
            set like_count = like_count + 1, updated_at = now(6)
            where magazine_id = :magazineId
            """, nativeQuery = true)
    int increaseLikeCount(@Param("magazineId") Long magazineId);

    /**
     * 좋아요 수 원자 감소. {@code like_count > 0} 가드를 두지 않는다 — 취소는 ACTIVE 행이 있을 때만 성공하고
     * 그 행은 +1과 같은 트랜잭션으로 커밋됐으므로, 정상 경로에서 0 미만이 될 수 없다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update meta_magazine
            set like_count = like_count - 1, updated_at = now(6)
            where magazine_id = :magazineId
            """, nativeQuery = true)
    int decreaseLikeCount(@Param("magazineId") Long magazineId);
}
