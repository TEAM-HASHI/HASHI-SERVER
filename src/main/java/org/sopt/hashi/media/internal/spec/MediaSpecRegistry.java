package org.sopt.hashi.media.internal.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.sopt.hashi.media.domain.ImageRole;
import org.sopt.hashi.media.domain.MediaPurpose;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class MediaSpecRegistry {

    private static final String V1_RESOURCE_PATH = "media-specs/v1.json";

    private final Map<Integer, MediaSpecDefinition> definitions;

    public MediaSpecRegistry(ObjectMapper objectMapper) {
        MediaSpecDefinition v1 = loadDefinition(objectMapper, V1_RESOURCE_PATH);
        this.definitions = Map.of(v1.version(), v1);
    }

    public Optional<MediaSpecSnapshot> find(int version) {
        return findDefinition(version).map(MediaSpecDefinition::snapshot);
    }

    public Optional<MediaSpecDefinition> findDefinition(int version) {
        return Optional.ofNullable(definitions.get(version));
    }

    private MediaSpecDefinition loadDefinition(ObjectMapper objectMapper, String resourcePath) {
        try (InputStream inputStream = new ClassPathResource(resourcePath).getInputStream()) {
            byte[] bytes = inputStream.readAllBytes();
            JsonNode manifest = objectMapper.readTree(bytes);
            int version = manifest.path("specVersion").asInt(-1);
            if (version < 1) {
                throw new IllegalStateException("media specVersion must be positive: " + resourcePath);
            }
            Map<ImageRole, MediaRoleSpec> roleSpecs = parseRoleSpecs(manifest.path("roles"));
            Map<MediaPurpose, List<ImageRole>> purposeRoles =
                    parsePurposeRoles(manifest.path("purposes"));
            return new MediaSpecDefinition(
                    version,
                    sha256(bytes),
                    purposeRoles,
                    roleSpecs
            );
        } catch (IOException e) {
            throw new IllegalStateException("failed to load media spec: " + resourcePath, e);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("invalid media spec: " + resourcePath, e);
        }
    }

    private Map<ImageRole, MediaRoleSpec> parseRoleSpecs(JsonNode rolesNode) {
        if (!rolesNode.isObject() || rolesNode.isEmpty()) {
            throw new IllegalArgumentException("media roles must be a non-empty object");
        }
        Map<ImageRole, MediaRoleSpec> roleSpecs = new EnumMap<>(ImageRole.class);
        rolesNode.properties().forEach(entry -> {
            ImageRole role = ImageRole.valueOf(entry.getKey());
            JsonNode roleNode = entry.getValue();
            JsonNode aspectRatio = roleNode.path("aspectRatio");
            JsonNode fallback = roleNode.path("noUpscaleFallback");
            List<MediaRenditionDimensions> candidates = new ArrayList<>();
            roleNode.path("candidates").forEach(candidate ->
                    candidates.add(new MediaRenditionDimensions(
                            requirePositiveInt(candidate, "width"),
                            requirePositiveInt(candidate, "height")
                    )));
            MediaRoleSpec previous = roleSpecs.put(role, new MediaRoleSpec(
                    requirePositiveInt(aspectRatio, "width"),
                    requirePositiveInt(aspectRatio, "height"),
                    requirePositiveInt(fallback, "minimumWidth"),
                    candidates
            ));
            if (previous != null) {
                throw new IllegalArgumentException("duplicate media role: " + role);
            }
        });
        return roleSpecs;
    }

    private Map<MediaPurpose, List<ImageRole>> parsePurposeRoles(JsonNode purposesNode) {
        if (!purposesNode.isObject() || purposesNode.isEmpty()) {
            throw new IllegalArgumentException("media purposes must be a non-empty object");
        }
        Map<MediaPurpose, List<ImageRole>> purposeRoles = new EnumMap<>(MediaPurpose.class);
        purposesNode.properties().forEach(entry -> {
            MediaPurpose purpose = MediaPurpose.valueOf(entry.getKey());
            if (!entry.getValue().isArray() || entry.getValue().isEmpty()) {
                throw new IllegalArgumentException("media purpose roles must not be empty");
            }
            List<ImageRole> roles = new ArrayList<>();
            entry.getValue().forEach(role -> roles.add(ImageRole.valueOf(role.asText())));
            if (roles.stream().distinct().count() != roles.size()) {
                throw new IllegalArgumentException("duplicate role in media purpose: " + purpose);
            }
            purposeRoles.put(purpose, List.copyOf(roles));
        });
        return purposeRoles;
    }

    private int requirePositiveInt(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (!value.canConvertToInt() || value.intValue() < 1) {
            throw new IllegalArgumentException("media spec field must be positive: " + fieldName);
        }
        return value.intValue();
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
