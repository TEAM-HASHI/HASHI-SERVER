package org.sopt.hashi.dev;

import java.util.List;

/** DB 데이터 초기화 결과 — 비운 테이블 목록. */
public record ClearDataResponse(
        int clearedTableCount,
        List<String> clearedTables) {

    public static ClearDataResponse from(List<String> clearedTables) {
        return new ClearDataResponse(clearedTables.size(), clearedTables);
    }
}
