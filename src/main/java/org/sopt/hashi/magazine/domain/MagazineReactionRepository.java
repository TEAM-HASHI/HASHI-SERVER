package org.sopt.hashi.magazine.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 리액션 행 조작은 모두 단일 SQL이다 — 조회 후 저장 방식은 동시 요청에서 유니크 위반 예외로 트랜잭션이
 * 롤백되므로, 멱등 등록·취소를 위해 "영향 행 수"로 실제 상태 변화를 판별한다.
 * 카운터 증감은 트랜잭션 밖({@code MagazineMetaUpdater}, @Async)에서 하므로 여기서는 magazine 행에
 * X 락을 거는 문장이 없다(INSERT의 FK 부모 S 락과 충돌할 UPDATE가 없어 교착이 생기지 않는다).
 */
public interface MagazineReactionRepository extends JpaRepository<MagazineReaction, Long> {

    boolean existsByMagazineIdAndUserIdAndReactionTypeAndStatus(
            Long magazineId, Long userId, MagazineReactionType reactionType, MagazineReactionStatus status);

    /**
     * 새 리액션 행 추가 — 이미 있으면(상태 무관) 유니크 제약이 막고 0을 돌려준다(INSERT IGNORE).
     * 0이면 호출 측이 {@link #reactivate}로 INACTIVE 행을 되살린다. INSERT를 먼저 하는 이유는
     * 행이 없을 때 UPDATE부터 하면 유니크 인덱스 갭 락이 잡혀 동시 INSERT와 교착하기 때문이다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            insert ignore into magazine_reaction
                (magazine_id, user_id, reaction_type, status, created_at, updated_at)
            values (:magazineId, :userId, :#{#reactionType.name()}, 'ACTIVE', now(6), now(6))
            """, nativeQuery = true)
    int insertIfAbsent(@Param("magazineId") Long magazineId,
                       @Param("userId") Long userId,
                       @Param("reactionType") MagazineReactionType reactionType);

    /** INACTIVE 행을 ACTIVE로 되돌린다 — 1이면 재등록, 0이면 이미 ACTIVE. {@link #insertIfAbsent}가 0을 준 뒤에만 부른다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update magazine_reaction
            set status = 'ACTIVE', updated_at = now(6)
            where magazine_id = :magazineId
              and user_id = :userId
              and reaction_type = :#{#reactionType.name()}
              and status = 'INACTIVE'
            """, nativeQuery = true)
    int reactivate(@Param("magazineId") Long magazineId,
                   @Param("userId") Long userId,
                   @Param("reactionType") MagazineReactionType reactionType);

    /** ACTIVE 행을 INACTIVE로 내린다 — 1이면 취소됨, 0이면 취소할 리액션이 없다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update magazine_reaction
            set status = 'INACTIVE', updated_at = now(6)
            where magazine_id = :magazineId
              and user_id = :userId
              and reaction_type = :#{#reactionType.name()}
              and status = 'ACTIVE'
            """, nativeQuery = true)
    int deactivate(@Param("magazineId") Long magazineId,
                   @Param("userId") Long userId,
                   @Param("reactionType") MagazineReactionType reactionType);
}
