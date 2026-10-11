package org.sopt.hashi.restaurant.internal.map.places;

import java.util.List;
import java.util.Objects;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.FailureKind;

public sealed interface PlacesSearchResult {

    record Candidates(List<PlacesCandidate> candidates) implements PlacesSearchResult {
        public Candidates {
            candidates = List.copyOf(candidates);
            if (candidates.isEmpty()) {
                throw new IllegalArgumentException("places candidates must not be empty");
            }
        }

        @Override
        public String toString() {
            return "PlacesSearchCandidates[redacted]";
        }
    }

    record NoResults() implements PlacesSearchResult {
    }

    record Failure(FailureKind kind, Integer httpStatus) implements PlacesSearchResult {
        public Failure {
            Objects.requireNonNull(kind, "kind must not be null");
        }
    }
}
