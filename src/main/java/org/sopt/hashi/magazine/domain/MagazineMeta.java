package org.sopt.hashi.magazine.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.sopt.hashi.BaseTimeEntity;

/**
 * 매거진 메타데이터(집계 카운터) — magazine과 1:1이며 매거진 생성 트랜잭션에서 함께 만들어 항상 존재한다.
 * 본문 행과 분리한 이유: 카운터는 쓰기가 몰리는 값이라 어드민 수정(dirty checking)과 행 락을 나누고,
 * 카운터가 늘어도(리뷰 수 등) magazine 스키마를 건드리지 않기 위함이다.
 * 값 갱신은 {@link MagazineMetaRepository}의 원자 UPDATE로만 하며 이 엔티티로 쓰지 않는다(updatable=false).
 */
@Getter
@Entity
@Table(name = "meta_magazine")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MagazineMeta extends BaseTimeEntity {

    @Id
    @Column(name = "magazine_id")
    private Long magazineId;

    @Column(name = "like_count", nullable = false, updatable = false)
    private long likeCount;

    private MagazineMeta(Long magazineId) {
        this.magazineId = magazineId;
        this.likeCount = 0;
    }

    public static MagazineMeta create(Long magazineId) {
        return new MagazineMeta(magazineId);
    }
}
