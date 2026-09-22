package org.sopt.hashi.magazine.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MagazineMetaRepository extends JpaRepository<MagazineMeta, Long> {

    /**
     * 좋아요 수만 읽는다 — 상세·좋아요 응답용. 비동기 갱신이라 직전 요청분이 아직 반영되지 않았을 수 있다.
     * -1 작업이 +1보다 먼저 실행된 찰나에는 음수일 수 있으므로 응답에는 {@link #findNonNegativeLikeCount}를 쓴다.
     */
    @Query("select m.likeCount from MagazineMeta m where m.magazineId = :magazineId")
    Optional<Long> findLikeCountById(@Param("magazineId") Long magazineId);

    /** 0 미만을 0으로 올린 좋아요 수 — 비동기 순서 뒤바뀜으로 잠깐 음수가 된 값을 응답에 그대로 내리지 않기 위함. 메타 행이 없으면 0. */
    default long findNonNegativeLikeCount(Long magazineId) {
        return Math.max(0L, findLikeCountById(magazineId).orElse(0L));
    }

    /** 좋아요 수 원자 증가 — 낙관적 락 대신 단일 UPDATE로 동시 갱신을 흡수한다(리뷰 통계와 같은 방식). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update meta_magazine
            set like_count = like_count + 1, updated_at = now(6)
            where magazine_id = :magazineId
            """, nativeQuery = true)
    int increaseLikeCount(@Param("magazineId") Long magazineId);

    /**
     * 좋아요 수 원자 감소. {@code like_count > 0} 가드를 두지 않는다 — 비동기 실행이라 -1 작업이 +1보다 먼저 올 수 있는데,
     * 가드가 있으면 그 -1이 무시되어 리액션이 없는데 카운터만 1로 남는다. 가드 없이 두면 잠깐 -1이 되었다가
     * 뒤따르는 +1로 0이 되어 순서와 무관하게 합이 맞고, 표시할 때만 음수를 0으로 보정한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update meta_magazine
            set like_count = like_count - 1, updated_at = now(6)
            where magazine_id = :magazineId
            """, nativeQuery = true)
    int decreaseLikeCount(@Param("magazineId") Long magazineId);
}
