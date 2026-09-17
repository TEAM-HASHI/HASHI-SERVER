package org.sopt.hashi.magazine.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MagazineMetaRepository extends JpaRepository<MagazineMeta, Long> {

    /** 좋아요 수만 읽는다 — 상세·좋아요 응답용. 비동기 갱신이라 직전 요청분이 아직 반영되지 않았을 수 있다. */
    @Query("select m.likeCount from MagazineMeta m where m.magazineId = :magazineId")
    Optional<Long> findLikeCountById(@Param("magazineId") Long magazineId);

    /** 좋아요 수 원자 증가 — 낙관적 락 대신 단일 UPDATE로 동시 갱신을 흡수한다(리뷰 통계와 같은 방식). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update meta_magazine
            set like_count = like_count + 1, updated_at = now(6)
            where magazine_id = :magazineId
            """, nativeQuery = true)
    int increaseLikeCount(@Param("magazineId") Long magazineId);

    /** 좋아요 수 원자 감소 — 0 밑으로 내려가지 않도록 가드한다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update meta_magazine
            set like_count = like_count - 1, updated_at = now(6)
            where magazine_id = :magazineId
              and like_count > 0
            """, nativeQuery = true)
    int decreaseLikeCount(@Param("magazineId") Long magazineId);
}
