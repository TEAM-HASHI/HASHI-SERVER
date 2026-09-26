package org.sopt.hashi.restaurant.domain;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.sopt.hashi.BaseTimeEntity;

/** 행정구와 별개인 운영 관광 지역. 식당 Aggregate에서는 ID로만 참조한다. */
@Getter
@Entity
@Table(name = "map_region")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MapRegion extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", length = 40, nullable = false, unique = true)
    private String code;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "latitude",
                    column = @Column(name = "cluster_latitude", precision = 9, scale = 6, nullable = false)),
            @AttributeOverride(name = "longitude",
                    column = @Column(name = "cluster_longitude", precision = 10, scale = 6, nullable = false))
    })
    private MapCoordinates clusterPosition;

    @Embedded
    private MapBounds cameraBounds;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "active", nullable = false)
    private boolean active;

    private MapRegion(String code, String name, MapCoordinates clusterPosition,
                      MapBounds cameraBounds, int displayOrder) {
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,39}")) {
            throw new IllegalArgumentException("관광 지역 코드는 대문자 영문으로 시작하는 40자 이내 코드여야 합니다");
        }
        if (name == null || name.isBlank() || name.codePointCount(0, name.length()) > 100) {
            throw new IllegalArgumentException("관광 지역 이름은 1~100자여야 합니다");
        }
        Objects.requireNonNull(cameraBounds, "cameraBounds");
        if (!cameraBounds.contains(clusterPosition) || displayOrder < 0) {
            throw new IllegalArgumentException("대표 좌표는 카메라 범위 안에 있고 표시 순서는 0 이상이어야 합니다");
        }
        this.code = code;
        this.name = name;
        this.clusterPosition = clusterPosition;
        this.cameraBounds = cameraBounds;
        this.displayOrder = displayOrder;
        this.active = false;
    }

    public static MapRegion create(String code, String name, MapCoordinates clusterPosition,
                                   MapBounds cameraBounds, int displayOrder) {
        return new MapRegion(code, name, clusterPosition, cameraBounds, displayOrder);
    }

    public void activate() {
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
    }
}
