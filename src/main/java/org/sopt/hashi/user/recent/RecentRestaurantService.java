package org.sopt.hashi.user.recent;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.user.code.UserErrorCode;
import org.sopt.hashi.user.collection.domain.SavedRestaurantRepository;
import org.sopt.hashi.user.domain.UserRepository;
import org.sopt.hashi.user.recent.RecentRestaurant.Position;
import org.sopt.hashi.user.recent.RecentRestaurantRequest.Visit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class RecentRestaurantService {
    private static final int HISTORY_LIMIT = 50;
    private static final int PAGE_SIZE = 10;
    private static final Comparator<RecentRestaurant> NEWEST_FIRST = Comparator.comparing(RecentRestaurant::position);

    private final RecentRestaurantRepository history;
    private final UserRepository users;
    private final RestaurantPort restaurants;
    private final SavedRestaurantRepository saves;
    private final CurrentUserProvider currentUser;
    private final Clock clock;

    RecentRestaurantService(RecentRestaurantRepository history, UserRepository users, RestaurantPort restaurants,
                            SavedRestaurantRepository saves, CurrentUserProvider currentUser,
                            @Qualifier("japanClock") Clock clock) {
        this.history = history;
        this.users = users;
        this.restaurants = restaurants;
        this.saves = saves;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @Transactional
    public void recordDetail(Long userId, Long restaurantId) {
        // 탈퇴 직후의 상세 요청도 공개 조회 자체는 성공시키고 이력만 생략한다.
        if (users.findByIdForUpdate(userId).isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        LocalDateTime viewedAt = utcTime(now, now);
        List<RecentRestaurant> existing = history.findByUserId(userId);
        // 같은 마이크로초에 상세 요청이 겹쳐도 마지막 요청이 목록의 맨 앞에 온다.
        LocalDateTime latest = existing.stream().map(RecentRestaurant::getViewedAt)
                .filter(java.util.Objects::nonNull).max(LocalDateTime::compareTo).orElse(null);
        if (latest != null && !viewedAt.isAfter(latest)) {
            now = latest.plus(1, ChronoUnit.MICROS).toInstant(ZoneOffset.UTC);
        }
        mergeLocked(userId, existing, List.of(new Visit(restaurantId, now)), now);
    }

    @Transactional
    public void merge(RecentRestaurantRequest request) {
        Long userId = currentUser.currentUserId();
        users.findByIdForUpdate(userId).orElseThrow(() -> new BusinessException(UserErrorCode.NOT_FOUND));
        mergeLocked(userId, history.findByUserId(userId), request.visits(), clock.instant());
    }

    private void mergeLocked(Long userId, List<RecentRestaurant> existing, List<Visit> visits, Instant now) {
        Map<Long, RecentRestaurant> byId = existing.stream().collect(Collectors.toMap(
                RecentRestaurant::getRestaurantId, Function.identity()));
        Set<Long> candidateIds = new HashSet<>(byId.keySet());
        visits.forEach(visit -> candidateIds.add(visit.restaurantId()));
        Set<Long> activeIds = Set.copyOf(restaurants.findActiveIds(candidateIds));
        for (Visit visit : visits) {
            LocalDateTime time = utcTime(visit.viewedAt(), now);
            if (activeIds.contains(visit.restaurantId())) {
                RecentRestaurant row = byId.computeIfAbsent(visit.restaurantId(),
                        id -> history.save(new RecentRestaurant(userId, id, time)));
                row.keepLatest(time);
            }
        }
        List<RecentRestaurant> remove = new ArrayList<>();
        List<RecentRestaurant> activeRows = new ArrayList<>();
        for (RecentRestaurant row : byId.values()) {
            if (activeIds.contains(row.getRestaurantId())) {
                activeRows.add(row);
            } else {
                remove.add(row);
            }
        }
        activeRows.sort(NEWEST_FIRST);
        remove.addAll(activeRows.subList(Math.min(HISTORY_LIMIT, activeRows.size()), activeRows.size()));
        if (!remove.isEmpty()) {
            history.deleteAllInBatch(remove);
        }
    }

    private static LocalDateTime utcTime(Instant time, Instant now) {
        if (time == null) {
            return null;
        }
        if (time.isBefore(Instant.EPOCH) || time.isAfter(now)) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        return LocalDateTime.ofInstant(time.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RecentRestaurantPage list(String cursor) {
        Long userId = currentUser.currentUserId();
        Position after = decode(cursor);
        List<RecentRestaurant> rows = history.findByUserId(userId).stream()
                .filter(row -> after == null || row.position().compareTo(after) > 0)
                .sorted(NEWEST_FIRST).toList();
        Set<Long> activeIds = Set.copyOf(restaurants.findActiveIds(
                rows.stream().map(RecentRestaurant::getRestaurantId).toList()));
        List<RecentRestaurant> page = rows.stream().filter(row -> activeIds.contains(row.getRestaurantId()))
                .limit(PAGE_SIZE + 1).toList();
        boolean hasNext = page.size() > PAGE_SIZE;
        if (hasNext) {
            page = page.subList(0, PAGE_SIZE);
        }
        if (page.isEmpty()) {
            return new RecentRestaurantPage(List.of(), null, false);
        }
        List<Long> ids = page.stream().map(RecentRestaurant::getRestaurantId).toList();
        Set<Long> saved = Set.copyOf(saves.findSavedRestaurantIds(userId, ids));
        List<RecentRestaurantPage.Card> cards = restaurants.findActiveDetails(ids).stream()
                .map(card -> RecentRestaurantPage.Card.of(card, saved.contains(card.id()))).toList();
        return new RecentRestaurantPage(cards, hasNext ? encode(page.getLast().position()) : null, hasNext);
    }

    private static String encode(Position position) {
        return (position.time() == null ? "legacy" : position.time().toString()) + "/" + position.id();
    }

    private static Position decode(String cursor) {
        if (cursor == null) {
            return null;
        }
        String[] parts = cursor.split("/", -1);
        if (parts.length == 2) {
            try {
                long id = Long.parseLong(parts[1]);
                if (id > 0) {
                    return new Position(parts[0].equals("legacy") ? null : LocalDateTime.parse(parts[0]), id);
                }
            } catch (DateTimeException | NumberFormatException ignored) {
                // 다른 요청 파라미터와 같은 400 응답을 사용한다.
            }
        }
        throw new BusinessException(CommonErrorCode.INVALID_INPUT);
    }
}
