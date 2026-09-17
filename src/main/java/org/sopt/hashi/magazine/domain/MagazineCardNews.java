package org.sopt.hashi.magazine.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/** 매거진 상세의 카드뉴스 이미지(캐러셀 1장). 배너·썸네일과 별개로 displayOrder 순으로 노출한다. */
@Getter
@Entity
@Table(name = "magazine_card_news")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MagazineCardNews extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "magazine_id", nullable = false)
    private Magazine magazine;

    @Column(name = "file_key", length = 500, nullable = false)
    private String fileKey;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    private MagazineCardNews(String fileKey, int displayOrder) {
        this.fileKey = fileKey;
        this.displayOrder = displayOrder;
    }

    public static MagazineCardNews create(String fileKey, int displayOrder) {
        return new MagazineCardNews(fileKey, displayOrder);
    }

    void assignMagazine(Magazine magazine) {
        this.magazine = magazine;
    }
}
