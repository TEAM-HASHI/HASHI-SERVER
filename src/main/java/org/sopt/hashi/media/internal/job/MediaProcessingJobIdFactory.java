package org.sopt.hashi.media.internal.job;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class MediaProcessingJobIdFactory {

    private static final UUID URL_NAMESPACE =
            UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");
    private static final String JOB_NAME_PREFIX = "urn:hashi:media:processing-job:v1";

    public UUID create(UUID assetId, String sourceVersionId, int specVersion) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        if (sourceVersionId == null || sourceVersionId.isBlank()) {
            throw new IllegalArgumentException("sourceVersionId must not be blank");
        }
        if (specVersion < 1) {
            throw new IllegalArgumentException("specVersion must be positive");
        }

        String name = "%s\n%s\n%s\n%d".formatted(
                JOB_NAME_PREFIX,
                assetId,
                sourceVersionId,
                specVersion
        );
        return uuidV5(URL_NAMESPACE, name);
    }

    static UUID uuidV5(UUID namespace, String name) {
        Objects.requireNonNull(namespace, "namespace must not be null");
        Objects.requireNonNull(name, "name must not be null");
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            ByteBuffer namespaceBytes = ByteBuffer.allocate(16)
                    .putLong(namespace.getMostSignificantBits())
                    .putLong(namespace.getLeastSignificantBits());
            sha1.update(namespaceBytes.array());
            byte[] digest = sha1.digest(name.getBytes(StandardCharsets.UTF_8));
            digest[6] = (byte) ((digest[6] & 0x0f) | 0x50);
            digest[8] = (byte) ((digest[8] & 0x3f) | 0x80);
            ByteBuffer uuidBytes = ByteBuffer.wrap(digest, 0, 16);
            return new UUID(uuidBytes.getLong(), uuidBytes.getLong());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is not available", e);
        }
    }
}
