package org.sopt.hashi.user.recent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Comparator;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Entity
@Table(name = "recent_restaurant")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class RecentRestaurant {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long userId;
    @Column(nullable = false)
    private Long restaurantId;
    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    private LocalDateTime viewedAt;

    RecentRestaurant(Long userId, Long restaurantId, LocalDateTime viewedAt) {
        this.userId = userId;
        this.restaurantId = restaurantId;
        this.viewedAt = viewedAt;
    }

    void keepLatest(LocalDateTime incoming) {
        if (incoming != null && (viewedAt == null || incoming.isAfter(viewedAt))) {
            viewedAt = incoming;
        }
    }

    Position position() {
        return new Position(viewedAt, viewedAt == null ? id : restaurantId);
    }

    /** 시간이 없는 기존 기기 이력은 입력 순서(id 오름차순)대로 모든 시간 있는 이력 뒤에 둔다. */
    record Position(LocalDateTime time, long id) implements Comparable<Position> {
        private static final Comparator<LocalDateTime> NEWEST_FIRST = Comparator.nullsLast(Comparator.reverseOrder());

        @Override
        public int compareTo(Position other) {
            int byTime = NEWEST_FIRST.compare(time, other.time);
            if (byTime != 0) {
                return byTime;
            }
            return time == null ? Long.compare(id, other.id) : Long.compare(other.id, id);
        }
    }
}
