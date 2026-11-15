package org.sopt.hashi.magazine.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.sopt.hashi.BaseTimeEntity;

/**
 * 매거진 리액션 — 회원 1명당 매거진 1건·리액션 종류 1개에 1행(uk_magazine_reaction_magazine_user_type).
 * 취소는 행을 지우지 않고 status를 INACTIVE로 내리며, 다시 누르면 같은 행을 ACTIVE로 되돌린다(이력 보존).
 * 사용자는 타 도메인이라 user_id 값만 보관한다. 행 추가·상태 변경은 {@link MagazineReactionRepository}의
 * 단일 SQL로만 하고, 집계는 {@link MagazineMeta}의 카운터가 담당한다.
 * 탈퇴한 회원의 행은 정리하지 않는다 — 카운트 보존이 목적이고 user_id는 값 참조라 정합성 문제가 없다.
 */
@Getter
@Entity
@Table(name = "magazine_reaction")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MagazineReaction extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "magazine_id", nullable = false)
    private Magazine magazine;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reaction_type", length = 20, nullable = false)
    private MagazineReactionType reactionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private MagazineReactionStatus status;
}
