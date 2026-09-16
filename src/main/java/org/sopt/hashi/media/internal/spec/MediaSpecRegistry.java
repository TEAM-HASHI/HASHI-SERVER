package org.sopt.hashi.media.internal.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class MediaSpecRegistry {

    private static final String V1_RESOURCE_PATH = "media-specs/v1.json";

    private final Map<Integer, MediaSpecSnapshot> snapshots;

    public MediaSpecRegistry(ObjectMapper objectMapper) {
        MediaSpecSnapshot v1 = loadSnapshot(objectMapper, V1_RESOURCE_PATH);
        this.snapshots = Map.of(v1.version(), v1);
    }

    public Optional<MediaSpecSnapshot> find(int version) {
        return Optional.ofNullable(snapshots.get(version));
    }

    private MediaSpecSnapshot loadSnapshot(ObjectMapper objectMapper, String resourcePath) {
        try (InputStream inputStream = new ClassPathResource(resourcePath).getInputStream()) {
            byte[] bytes = inputStream.readAllBytes();
            JsonNode manifest = objectMapper.readTree(bytes);
            int version = manifest.path("specVersion").asInt(-1);
            if (version < 1) {
                throw new IllegalStateException("media specVersion must be positive: " + resourcePath);
            }
            return new MediaSpecSnapshot(version, sha256(bytes));
        } catch (IOException e) {
            throw new IllegalStateException("failed to load media spec: " + resourcePath, e);
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
