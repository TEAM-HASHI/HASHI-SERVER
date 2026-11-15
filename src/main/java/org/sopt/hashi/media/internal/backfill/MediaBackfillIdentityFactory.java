package org.sopt.hashi.media.internal.backfill;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** association과 source를 길이 구분하여 hash한다. 원시 콘텐츠 ID는 결과에 보관하지 않는다. */
public class MediaBackfillIdentityFactory {

    public String create(String associationKind, long associationId, String slot, LegacyImageSource source) {
        if (associationKind == null || associationKind.isBlank() || associationId < 1
                || slot == null || slot.isBlank()) {
            throw new IllegalArgumentException("invalid backfill association");
        }
        Objects.requireNonNull(source, "source is required");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            append(digest, "hashi:media:backfill:v1");
            append(digest, associationKind);
            append(digest, Long.toString(associationId));
            append(digest, slot);
            append(digest, source.bucket());
            append(digest, source.objectKey());
            append(digest, source.versionId() == null ? "etag" : "version");
            append(digest, source.versionId() == null ? source.eTag() : source.versionId());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private void append(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
