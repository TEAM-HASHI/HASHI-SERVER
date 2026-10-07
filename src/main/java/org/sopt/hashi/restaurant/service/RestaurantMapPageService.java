package org.sopt.hashi.restaurant.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.restaurant.domain.RestaurantMapSort;
import org.sopt.hashi.restaurant.dto.RestaurantMapPageRequest;
import org.sopt.hashi.restaurant.dto.RestaurantMapPageResponse;
import org.sopt.hashi.restaurant.dto.RestaurantMapPageResponse.QueryResponse;
import org.sopt.hashi.restaurant.internal.map.MapCursorCodec;
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
    private final Semaphore activeRequests;

    public RestaurantMapPageService(RestaurantMapService mapService, RestaurantMapPageReader reader,
                                    RedisMapSessionStore store, MapCursorCodec cursors,
                                    MapSessionLimits limits) {
        this.mapService = mapService;
        this.reader = reader;
        this.store = store;
        this.cursors = cursors;
        this.limits = limits;
        this.activeRequests = new Semaphore(Math.max(1, Math.min(16, limits.getConcurrentRequests())));
    }

    public RestaurantMapPageResponse getPage(RestaurantMapPageRequest request) {
        return getPage(request, "internal");
    }

    public RestaurantMapPageResponse getPage(RestaurantMapPageRequest request, String remoteAddress) {
        cursors.requireConfigured();
        limits.validate();
        if (!activeRequests.tryAcquire()) {
            throw new BusinessException(RestaurantErrorCode.MAP_CAPACITY_EXCEEDED);
        }
        try {
            return readPage(request, remoteAddress);
        } finally {
            activeRequests.release();
        }
    }

    private RestaurantMapPageResponse readPage(RestaurantMapPageRequest request, String remoteAddress) {
        MapSessionId id;
        MapQuerySession session;
        RestaurantMapSort sort = request.sort();
        int start = 0;
        if (request.cursor() != null) {
            var cursor = cursors.decode(request.cursor());
            id = cursor.session();
            sort = cursor.sort();
            start = cursor.position();
            store.admit(cursors.callerKey(remoteAddress), false);
            session = store.find(id);
        } else if (request.querySessionId() != null) {
            id = MapSessionId.parse(request.querySessionId());
            store.admit(cursors.callerKey(remoteAddress), false);
            session = store.find(id);
        } else {
            var startedAt = store.admit(cursors.callerKey(remoteAddress), true);
            var snapshot = mapService.findCandidates(request.criteria(), limits.candidateCapacity());
            var recommendation = new ArrayList<>(snapshot.candidates());
            Collections.shuffle(recommendation);
            session = new MapQuerySession(MapQuerySession.SCHEMA_VERSION, UUID.randomUUID(), request.criteria(),
                    recommendation, snapshot.rankingAsOf(), startedAt.plus(limits.getMaxLifetime()));
            id = store.save(session);
        }
        if (start > session.candidates().size()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        var page = reader.read(session, sort, start);
        var expiresAt = store.touch(id, session);
        return new RestaurantMapPageResponse(page.content(),
                page.hasNext() ? cursors.encode(id, sort, page.nextPosition()) : null, page.hasNext(),
                id.value(), expiresAt, session.rankingAsOf(), QueryResponse.from(session.criteria(), sort));
    }
}
