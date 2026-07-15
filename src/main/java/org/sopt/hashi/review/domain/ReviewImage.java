package org.sopt.hashi.review.domain;

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

@Getter
@Entity
@Table(name = "review_image")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewImage extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_id", nullable = false)
    private Review review;

    @Column(name = "file_key", length = 500, nullable = false)
    private String fileKey;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    private ReviewImage(String fileKey, int displayOrder) {
        this.fileKey = fileKey;
        this.displayOrder = displayOrder;
    }

    public static ReviewImage create(String fileKey, int displayOrder) {
        return new ReviewImage(fileKey, displayOrder);
    }

    void assignReview(Review review) {
        this.review = review;
    }
}
