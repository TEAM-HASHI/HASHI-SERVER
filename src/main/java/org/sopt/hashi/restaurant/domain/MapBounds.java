package org.sopt.hashi.restaurant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 경계를 포함하는 BBOX. 도쿄 1차에서는 날짜변경선 횡단(west > east)을 지원하지 않는다. */
@Getter
@Embeddable
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MapBounds {

    @Column(name = "south", precision = 9, scale = 6, nullable = false)
    private BigDecimal south;

    @Column(name = "north", precision = 9, scale = 6, nullable = false)
    private BigDecimal north;

    @Column(name = "west", precision = 10, scale = 6, nullable = false)
    private BigDecimal west;

    @Column(name = "east", precision = 10, scale = 6, nullable = false)
    private BigDecimal east;

    private MapBounds(BigDecimal south, BigDecimal north, BigDecimal west, BigDecimal east) {
        this.south = MapCoordinates.requireLatitude(south);
        this.north = MapCoordinates.requireLatitude(north);
        this.west = MapCoordinates.requireLongitude(west);
        this.east = MapCoordinates.requireLongitude(east);
        boolean hasArea = this.south.compareTo(this.north) < 0 && this.west.compareTo(this.east) < 0;
        if (!hasArea) {
            throw new IllegalArgumentException("BBOX는 south < north, west < east여야 합니다");
        }
    }

    public static MapBounds of(BigDecimal south, BigDecimal north, BigDecimal west, BigDecimal east) {
        return new MapBounds(south, north, west, east);
    }

    public boolean contains(MapCoordinates coordinates) {
        Objects.requireNonNull(coordinates, "coordinates");
        return coordinates.getLatitude().compareTo(south) >= 0
                && coordinates.getLatitude().compareTo(north) <= 0
                && coordinates.getLongitude().compareTo(west) >= 0
                && coordinates.getLongitude().compareTo(east) <= 0;
    }
}
