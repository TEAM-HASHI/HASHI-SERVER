package org.sopt.hashi.media;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 클라이언트가 반응형 이미지 후보를 선택할 수 있는 공통 이미지 응답. */
public record MediaImage(
        UUID assetId,
        MediaImageRole role,
        MediaImageStatus status,
        Source defaultSource,
        List<SourceSet> sourceSets
) {

    public MediaImage {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(status, "status must not be null");
        sourceSets = List.copyOf(Objects.requireNonNull(sourceSets, "sourceSets must not be null"));
        if (status == MediaImageStatus.READY && (defaultSource == null || sourceSets.isEmpty())) {
            throw new IllegalArgumentException("READY image requires sources");
        }
        if (status != MediaImageStatus.READY && (defaultSource != null || !sourceSets.isEmpty())) {
            throw new IllegalArgumentException("non-READY image must not expose sources");
        }
    }

    public record Source(String url, int width, int height, String mimeType) {

        public Source {
            requireSource(url, width, height, mimeType);
        }
    }

    public record SourceSet(String mimeType, List<Candidate> candidates) {

        public SourceSet {
            if (mimeType == null || mimeType.isBlank()) {
                throw new IllegalArgumentException("mimeType must not be blank");
            }
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates must not be null"));
            if (candidates.isEmpty()) {
                throw new IllegalArgumentException("candidates must not be empty");
            }
        }
    }

    public record Candidate(String url, int width, int height) {

        public Candidate {
            if (url == null || url.isBlank() || width < 1 || height < 1) {
                throw new IllegalArgumentException("image candidate values are invalid");
            }
        }
    }

    private static void requireSource(String url, int width, int height, String mimeType) {
        if (url == null || url.isBlank() || mimeType == null || mimeType.isBlank()
                || width < 1 || height < 1) {
            throw new IllegalArgumentException("image source values are invalid");
        }
    }
}
