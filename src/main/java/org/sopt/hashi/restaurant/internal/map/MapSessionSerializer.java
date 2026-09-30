package org.sopt.hashi.restaurant.internal.map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.stereotype.Component;

/** 기본 typing을 사용하지 않고 버전이 있는 구체 record로만 역직렬화한다. */
@Component
public class MapSessionSerializer {
    private final ObjectMapper mapper = new ObjectMapper(JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder().maxNumberLength(MapQuerySession.MAX_BYTES)
                    .maxStringLength(MapQuerySession.MAX_BYTES).maxNestingDepth(16).build()).build())
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public String serialize(MapQuerySession session) {
        try {
            String json = mapper.writeValueAsString(session);
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
            MapQuerySession session = mapper.readValue(json, MapQuerySession.class);
            if (session == null) {
                throw new IllegalArgumentException();
            }
            return session;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            // 구버전/손상 payload의 파서 메시지에는 검색어가 있을 수 있으므로 cause를 전달하지 않는다.
            throw new BusinessException(RestaurantErrorCode.MAP_SESSION_EXPIRED);
        }
    }
}
