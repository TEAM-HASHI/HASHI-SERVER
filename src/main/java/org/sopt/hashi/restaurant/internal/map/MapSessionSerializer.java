package org.sopt.hashi.restaurant.internal.map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.restaurant.domain.MapSearchCriteria;
import org.sopt.hashi.restaurant.domain.RestaurantMapCandidate;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.stereotype.Component;

/** 기본 typing을 사용하지 않고 버전이 있는 구체 record로만 역직렬화한다. */
@Component
public class MapSessionSerializer {
    private static final int FORMAT_VERSION = 1;
    private final ObjectMapper mapper = new ObjectMapper(JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder().maxNumberLength(MapQuerySession.MAX_BYTES)
                    .maxStringLength(MapQuerySession.MAX_BYTES).maxNestingDepth(16).build()).build())
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public String serialize(MapQuerySession session) {
        try {
            String json = mapper.writeValueAsString(SessionPayload.from(session));
            if (json.getBytes(StandardCharsets.UTF_8).length > MapQuerySession.MAX_BYTES) {
                throw new BusinessException(RestaurantErrorCode.MAP_CAPACITY_EXCEEDED);
            }
            return json;
        } catch (JsonProcessingException exception) {
            throw new BusinessException(RestaurantErrorCode.MAP_SESSION_UNAVAILABLE);
        }
    }

    public MapQuerySession deserialize(String json) {
        try {
            if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MapQuerySession.MAX_BYTES) {
                throw new IllegalArgumentException();
            }
            SessionPayload payload = mapper.readValue(json, SessionPayload.class);
            if (payload == null || payload.formatVersion() != FORMAT_VERSION) {
                throw new IllegalArgumentException();
            }
            return payload.toSession();
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            // 구버전/손상 payload의 파서 메시지에는 검색어가 있을 수 있으므로 cause를 전달하지 않는다.
            throw new BusinessException(RestaurantErrorCode.MAP_SESSION_EXPIRED);
        }
    }

    @JsonPropertyOrder({"formatVersion", "schemaVersion", "id", "criteria", "candidates", "rankingAsOf", "expiresAt"})
    private record SessionPayload(int formatVersion, int schemaVersion, UUID id, MapSearchCriteria criteria,
                                  List<CandidateRow> candidates, Instant rankingAsOf, Instant expiresAt) {

        private static SessionPayload from(MapQuerySession session) {
            return new SessionPayload(FORMAT_VERSION, session.schemaVersion(), session.id(), session.criteria(),
                    session.candidates().stream().map(CandidateRow::from).toList(),
                    session.rankingAsOf(), session.expiresAt());
        }

        private MapQuerySession toSession() {
            if (candidates == null || candidates.stream().anyMatch(candidate -> candidate == null)) {
                throw new IllegalArgumentException("Invalid map session payload");
            }
            return new MapQuerySession(schemaVersion, id, criteria,
                    candidates.stream().map(CandidateRow::toCandidate).toList(), rankingAsOf, expiresAt);
        }
    }

    /** 후보의 세 값은 한 JSON tuple로 묶어 순서 어긋남 없이 반복 필드명만 제거한다. */
    private record CandidateRow(Long restaurantId, BigDecimal rating, Long reviewCount) {

        private CandidateRow {
            if (restaurantId == null || rating == null || reviewCount == null) {
                throw new IllegalArgumentException("Invalid map candidate payload");
            }
        }

        private static CandidateRow from(RestaurantMapCandidate candidate) {
            return new CandidateRow(candidate.restaurantId(), candidate.rating(), candidate.reviewCount());
        }

        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        private static CandidateRow fromJson(List<BigDecimal> values) {
            if (values == null || values.size() != 3 || values.stream().anyMatch(value -> value == null)) {
                throw new IllegalArgumentException("Invalid map candidate payload");
            }
            try {
                return new CandidateRow(values.get(0).longValueExact(), values.get(1), values.get(2).longValueExact());
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("Invalid map candidate payload");
            }
        }

        @JsonValue
        private List<Number> toJson() {
            return List.of(restaurantId, rating, reviewCount);
        }

        private RestaurantMapCandidate toCandidate() {
            return new RestaurantMapCandidate(restaurantId, rating, reviewCount);
        }
    }
}
