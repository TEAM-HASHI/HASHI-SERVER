package org.sopt.hashi.restaurant.internal.map;

import java.util.List;

/** 외부 예외/원문을 운반하지 않는다. 관측에는 실패 종류와 HTTP 상태만 사용한다. */
public sealed interface GeocodingResult {

    record Candidates(List<GeocodingCandidate> candidates) implements GeocodingResult {
        public Candidates {
            candidates = List.copyOf(candidates);
            if (candidates.isEmpty()) {
                throw new IllegalArgumentException("geocoding candidates must not be empty");
            }
        }

        @Override
        public String toString() {
            return "GeocodingCandidates[redacted]";
        }
    }

    record NoResults() implements GeocodingResult {
    }

    record Failure(FailureKind kind, Integer httpStatus) implements GeocodingResult {
    }

    enum FailureKind {
        DISABLED,
        INVALID_REQUEST,
        ACCESS_DENIED,
        CONFIGURATION_ERROR,
        QUOTA_EXCEEDED,
        TRANSIENT_ERROR,
        TIMEOUT,
        CONNECTION_ERROR,
        INVALID_RESPONSE,
        RESPONSE_TOO_LARGE,
        REDIRECT_REJECTED
    }
}
