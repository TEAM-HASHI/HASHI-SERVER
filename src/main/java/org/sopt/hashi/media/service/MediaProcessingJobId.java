package org.sopt.hashi.media.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

final class MediaProcessingJobId {

    // 이미 발급된 job ID의 재현성을 위해 변경하지 않는다.
    private static final UUID NAMESPACE = UUID.fromString("5d167dc9-9bfd-5f4e-a7a0-46b10b4de90d");

    private MediaProcessingJobId() {
    }

    static UUID from(UUID assetId, String sourceVersionId, int specVersion) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        if (sourceVersionId == null || sourceVersionId.isBlank()) {
            throw new IllegalArgumentException("sourceVersionId must not be blank");
        }
        if (specVersion < 1) {
            throw new IllegalArgumentException("specVersion must be positive");
        }

        byte[] sourceVersionBytes = sourceVersionId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer canonicalName = ByteBuffer.allocate(
                Long.BYTES * 2 + Integer.BYTES + sourceVersionBytes.length + Integer.BYTES);
        canonicalName.putLong(assetId.getMostSignificantBits());
        canonicalName.putLong(assetId.getLeastSignificantBits());
        canonicalName.putInt(sourceVersionBytes.length);
        canonicalName.put(sourceVersionBytes);
        canonicalName.putInt(specVersion);

        MessageDigest sha1 = sha1();
        sha1.update(toBytes(NAMESPACE));
        byte[] hash = sha1.digest(canonicalName.array());
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);

        ByteBuffer uuidBytes = ByteBuffer.wrap(Arrays.copyOf(hash, 16));
        return new UUID(uuidBytes.getLong(), uuidBytes.getLong());
    }

    private static byte[] toBytes(UUID value) {
        return ByteBuffer.allocate(Long.BYTES * 2)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 must be available for UUIDv5", e);
        }
    }
}
