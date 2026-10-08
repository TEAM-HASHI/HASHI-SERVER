package org.sopt.hashi.restaurant.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.restaurant.domain.RestaurantMapSort;
import org.sopt.hashi.restaurant.dto.RestaurantMapPageRequest;
import org.sopt.hashi.restaurant.dto.RestaurantMapPageResponse;
import org.sopt.hashi.restaurant.dto.RestaurantMapPageResponse.QueryResponse;
import org.sopt.hashi.restaurant.dto.RestaurantMapPageResponse.SearchResultResponse;
import org.sopt.hashi.restaurant.internal.map.MapCursorCodec;
import org.sopt.hashi.restaurant.internal.map.MapCapacityMetrics;
import org.sopt.hashi.restaurant.internal.map.MapCapacityMetrics.Operation;
import org.sopt.hashi.restaurant.internal.map.MapCapacityMetrics.Reason;
import org.sopt.hashi.restaurant.internal.map.MapCapacityMetrics.Stage;
import org.sopt.hashi.restaurant.internal.map.MapQuerySession;
import org.sopt.hashi.restaurant.internal.map.MapSessionId;
import org.sopt.hashi.restaurant.internal.map.MapSessionLimits;
import org.sopt.hashi.restaurant.internal.map.RedisMapSessionStore;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** DB 단계와 Redis 단계를 분리한다. 상위 transaction이 Redis 대기까지 연결을 점유하지 못하게 한다. */
@Service
@Transactional(propagation = Propagation.NEVER)
public class RestaurantMapPageService {
    private final RestaurantMapService mapService;
    private final RestaurantMapPageReader reader;
    private final RedisMapSessionStore store;
    private final MapCursorCodec cursors;
    private final MapSessionLimits limits;
    private final MapCapacityMetrics metrics;
    private final Semaphore activeRequests;

    public RestaurantMapPageService(RestaurantMapService mapService, RestaurantMapPageReader reader,
                                    RedisMapSessionStore store, MapCursorCodec cursors,
                                    MapSessionLimits limits, MapCapacityMetrics metrics) {
        this.mapService = mapService;
        this.reader = reader;
        this.store = store;
        this.cursors = cursors;
        this.limits = limits;
        this.metrics = metrics;
        this.activeRequests = new Semaphore(Math.max(1, Math.min(16, limits.getConcurrentRequests())));
    }

    public RestaurantMapPageResponse getPage(RestaurantMapPageRequest request) {
        return getPage(request, "internal");
    }

    public RestaurantMapPageResponse getPage(RestaurantMapPageRequest request, String remoteAddress) {
        cursors.requireConfigured();
        limits.validate();
        if (!activeRequests.tryAcquire()) {
            metrics.rejected(Reason.CONCURRENT_REQUESTS);
            throw new BusinessException(RestaurantErrorCode.MAP_CAPACITY_EXCEEDED);
        }
        try {
            return readPage(request, remoteAddress);
        } finally {
            activeRequests.release();
        }
    }

    private RestaurantMapPageResponse readPage(RestaurantMapPageRequest request, String remoteAddress) {
        Operation operation = operation(request);
        MapSessionId id;
        MapQuerySession session;
        RestaurantMapSort sort = request.sort();
        int start = 0;
        if (request.cursor() != null) {
            var cursor = cursors.decode(request.cursor());
            var sessionId = cursor.session();
            id = sessionId;
            sort = cursor.sort();
            start = cursor.position();
            metrics.record(operation, Stage.ADMIT,
                    () -> store.admit(cursors.callerKey(remoteAddress), false));
            session = metrics.record(operation, Stage.LOAD_SESSION, () -> store.find(sessionId));
        } else if (request.querySessionId() != null) {
            var sessionId = MapSessionId.parse(request.querySessionId());
            id = sessionId;
            metrics.record(operation, Stage.ADMIT,
                    () -> store.admit(cursors.callerKey(remoteAddress), false));
            session = metrics.record(operation, Stage.LOAD_SESSION, () -> store.find(sessionId));
        } else {
            var startedAt = metrics.record(operation, Stage.ADMIT,
                    () -> store.admit(cursors.callerKey(remoteAddress), true));
            RestaurantMapService.CandidateSnapshot snapshot;
            try {
                snapshot = metrics.record(operation, Stage.CANDIDATES,
                        () -> mapService.findCandidates(request.criteria(), limits.candidateCapacity()));
            } catch (BusinessException exception) {
                if (exception.getErrorCode() == RestaurantErrorCode.MAP_CAPACITY_EXCEEDED) {
                    metrics.rejected(Reason.CANDIDATE_COUNT);
                }
                throw exception;
            }
            var recommendation = new ArrayList<>(snapshot.candidates());
            Collections.shuffle(recommendation);
            Instant hardExpiresAt = startedAt.plus(limits.getMaxLifetime());
            if (snapshot.resultExtent() != null && snapshot.resultExtent().earliestValidUntil() != null
                    && snapshot.resultExtent().earliestValidUntil().isBefore(hardExpiresAt)) {
                hardExpiresAt = snapshot.resultExtent().earliestValidUntil();
            }
            var createdSession = new MapQuerySession(
                    MapQuerySession.SCHEMA_VERSION, UUID.randomUUID(), request.criteria(),
                    recommendation, snapshot.resultExtent(), snapshot.rankingAsOf(), hardExpiresAt);
            session = createdSession;
            id = metrics.record(operation, Stage.SAVE, () -> store.save(createdSession));
        }
        if (start > session.candidates().size()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        MapQuerySession pageSession = session;
        MapSessionId pageSessionId = id;
        RestaurantMapSort pageSort = sort;
        int pageStart = start;
        var page = metrics.record(operation, Stage.READ_PAGE,
                () -> reader.read(pageSession, pageSort, pageStart));
        var expiresAt = metrics.record(operation, Stage.TOUCH,
                () -> store.touch(pageSessionId, pageSession));
        return new RestaurantMapPageResponse(page.content(),
                page.hasNext() ? cursors.encode(id, sort, page.nextPosition()) : null, page.hasNext(),
                id.value(), expiresAt, session.rankingAsOf(), SearchResultResponse.from(session.searchResult()),
                QueryResponse.from(session.criteria(), sort));
    }

    private static Operation operation(RestaurantMapPageRequest request) {
        if (request.cursor() != null) {
            return Operation.NEXT_PAGE;
        }
        return request.querySessionId() != null ? Operation.SORT_CHANGE : Operation.NEW_QUERY;
    }
}
