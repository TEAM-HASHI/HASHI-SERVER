package org.sopt.hashi.restaurant.internal.map.places;

import java.util.Objects;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.FailureKind;

public sealed interface PlaceDetailsResult {

    record Place(PlacesCandidate candidate) implements PlaceDetailsResult {
        public Place {
            Objects.requireNonNull(candidate, "candidate must not be null");
        }

        @Override
        public String toString() {
            return "PlaceDetails[redacted]";
        }
    }

    record NoResults() implements PlaceDetailsResult {
    }

    record Failure(FailureKind kind, Integer httpStatus) implements PlaceDetailsResult {
        public Failure {
            Objects.requireNonNull(kind, "kind must not be null");
        }
    }
}
