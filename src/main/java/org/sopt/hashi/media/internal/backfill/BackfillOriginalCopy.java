package org.sopt.hashi.media.internal.backfill;

/** DB job에 고정할 복사본. legacy 경로나 metadata를 외부 응답에 노출하지 않는다. */
public record BackfillOriginalCopy(String objectKey, String versionId, String eTag,
                                   String contentType, long bytes, String identityHash) {

    public BackfillOriginalCopy {
        boolean hasVersion = versionId != null && !versionId.isBlank() && !"null".equals(versionId);
        boolean hasMetadata = objectKey != null && !objectKey.isBlank()
                && eTag != null && !eTag.isBlank() && contentType != null && !contentType.isBlank();
        if (!hasVersion || !hasMetadata || bytes < 1
                || identityHash == null || !identityHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid backfill original copy");
        }
    }

    @Override
    public String toString() {
        return "BackfillOriginalCopy[redacted]";
    }
}
