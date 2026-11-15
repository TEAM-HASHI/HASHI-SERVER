package org.sopt.hashi.media.internal.cleanup;

/** acknowledgedDeletes는 중복 요청을 포함할 수 있어 실제 삭제 파일 수나 과금 수량이 아니다. */
public record MediaObjectPurgeResult(boolean complete, int acknowledgedDeletes) {

    public MediaObjectPurgeResult {
        if (acknowledgedDeletes < 0) {
            throw new IllegalArgumentException("acknowledged deletes must not be negative");
        }
    }
}
