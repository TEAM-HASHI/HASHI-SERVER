package org.sopt.hashi.media.internal.spec;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import org.sopt.hashi.media.domain.ImageRole;
import org.sopt.hashi.media.domain.MediaPurpose;

final class MediaSpecManifestValidator {

    private static final int MANIFEST_SCHEMA_VERSION = 1;
    private static final String PROCESSOR_REVISION = "sharp-webp-v1";
    private static final Set<String> ROOT_REQUIRED_FIELDS = Set.of(
            "manifestSchemaVersion", "specVersion", "processorRevision",
            "output", "purposes", "roles");
    private static final Set<String> ROOT_ALLOWED_FIELDS = Set.of(
            "$schema", "manifestSchemaVersion", "specVersion", "processorRevision",
            "output", "purposes", "roles");
    private static final Set<String> OUTPUT_FIELDS = Set.of(
            "format", "mimeType", "withoutEnlargement", "metadata",
            "colorSpace", "dimensionRounding");
    private static final Set<String> ROLE_FIELDS = Set.of(
            "aspectRatio", "fit", "position", "quality", "defaultWidth",
            "candidates", "noUpscaleFallback");
    private static final Set<String> DIMENSION_FIELDS = Set.of("width", "height");
    private static final Set<String> FALLBACK_FIELDS = Set.of("selection", "minimumWidth");

    private MediaSpecManifestValidator() {
    }

    static void validate(JsonNode manifest, int expectedSpecVersion) {
        requireObject(manifest, "media manifest must be an object");
        requireFields(manifest, ROOT_REQUIRED_FIELDS, ROOT_ALLOWED_FIELDS, "media manifest");
        requireInt(manifest, "manifestSchemaVersion", MANIFEST_SCHEMA_VERSION,
                MANIFEST_SCHEMA_VERSION);
        requireInt(manifest, "specVersion", expectedSpecVersion, expectedSpecVersion);
        requireText(manifest, "processorRevision", PROCESSOR_REVISION);
        if (manifest.has("$schema") && !manifest.path("$schema").isTextual()) {
            throw invalid("media manifest $schema must be text");
        }

        validateOutput(manifest.path("output"));
        validatePurposes(manifest.path("purposes"));
        validateRoles(manifest.path("roles"));
    }

    private static void validateOutput(JsonNode output) {
        requireObject(output, "media output must be an object");
        requireFields(output, OUTPUT_FIELDS, OUTPUT_FIELDS, "media output");
        requireText(output, "format", "webp");
        requireText(output, "mimeType", "image/webp");
        requireBoolean(output, "withoutEnlargement", true);
        requireText(output, "metadata", "strip");
        requireText(output, "colorSpace", "srgb");
        requireText(output, "dimensionRounding", "half-up");
    }

    private static void validatePurposes(JsonNode purposes) {
        requireObject(purposes, "media purposes must be an object");
        requireEnumKeys(purposes, MediaPurpose.class, "media purposes");
        purposes.properties().forEach(entry -> {
            JsonNode roles = entry.getValue();
            if (!roles.isArray() || roles.isEmpty()) {
                throw invalid("media purpose roles must be a non-empty array");
            }
            Set<ImageRole> uniqueRoles = EnumSet.noneOf(ImageRole.class);
            roles.forEach(roleNode -> {
                if (!roleNode.isTextual()) {
                    throw invalid("media purpose role must be text");
                }
                ImageRole role;
                try {
                    role = ImageRole.valueOf(roleNode.textValue());
                } catch (IllegalArgumentException exception) {
                    throw invalid("media purpose role is unsupported");
                }
                if (!uniqueRoles.add(role)) {
                    throw invalid("media purpose role must be unique");
                }
            });
        });
    }

    private static void validateRoles(JsonNode roles) {
        requireObject(roles, "media roles must be an object");
        requireEnumKeys(roles, ImageRole.class, "media roles");
        roles.properties().forEach(entry -> validateRole(entry.getKey(), entry.getValue()));
    }

    private static void validateRole(String roleName, JsonNode role) {
        requireObject(role, "media role must be an object");
        requireFields(role, ROLE_FIELDS, ROLE_FIELDS, "media role " + roleName);

        JsonNode aspectRatio = role.path("aspectRatio");
        requireObject(aspectRatio, "media role aspectRatio must be an object");
        requireFields(aspectRatio, DIMENSION_FIELDS, DIMENSION_FIELDS,
                "media role aspectRatio");
        int ratioWidth = requireInt(aspectRatio, "width", 1, Integer.MAX_VALUE);
        int ratioHeight = requireInt(aspectRatio, "height", 1, Integer.MAX_VALUE);

        requireText(role, "fit", "cover");
        requireText(role, "position", "centre");
        requireInt(role, "quality", 1, 100);
        int defaultWidth = requireInt(role, "defaultWidth", 1, Integer.MAX_VALUE);

        JsonNode candidates = role.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            throw invalid("media role candidates must be a non-empty array");
        }
        Set<Integer> widths = new HashSet<>();
        int previousWidth = 0;
        for (JsonNode candidate : candidates) {
            requireObject(candidate, "media role candidate must be an object");
            requireFields(candidate, DIMENSION_FIELDS, DIMENSION_FIELDS,
                    "media role candidate");
            int width = requireInt(candidate, "width", 1, Integer.MAX_VALUE);
            int height = requireInt(candidate, "height", 1, Integer.MAX_VALUE);
            if (!widths.add(width) || width <= previousWidth) {
                throw invalid("media role candidate widths must be unique and increasing");
            }
            if (height != roundHalfUp((long) width * ratioHeight, ratioWidth)) {
                throw invalid("media role candidate height must follow aspectRatio");
            }
            previousWidth = width;
        }
        if (!widths.contains(defaultWidth)) {
            throw invalid("media role defaultWidth must match a candidate width");
        }

        JsonNode fallback = role.path("noUpscaleFallback");
        requireObject(fallback, "media role noUpscaleFallback must be an object");
        requireFields(fallback, FALLBACK_FIELDS, FALLBACK_FIELDS,
                "media role noUpscaleFallback");
        requireText(fallback, "selection", "largest-croppable-width");
        requireInt(fallback, "minimumWidth", 1, 1);
    }

    private static <E extends Enum<E>> void requireEnumKeys(
            JsonNode node, Class<E> enumType, String subject) {
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        Set<String> expected = new HashSet<>();
        for (E value : enumType.getEnumConstants()) {
            expected.add(value.name());
        }
        if (!actual.equals(expected)) {
            throw invalid(subject + " must exactly match server capabilities");
        }
    }

    private static void requireFields(
            JsonNode node, Set<String> required, Set<String> allowed, String subject) {
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.containsAll(required) || !allowed.containsAll(actual)) {
            throw invalid(subject + " fields do not match contract v1");
        }
    }

    private static void requireObject(JsonNode node, String message) {
        if (node == null || !node.isObject()) {
            throw invalid(message);
        }
    }

    private static void requireText(JsonNode node, String field, String expected) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || !expected.equals(value.textValue())) {
            throw invalid("media spec text field is unsupported: " + field);
        }
    }

    private static void requireBoolean(JsonNode node, String field, boolean expected) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean() || value.booleanValue() != expected) {
            throw invalid("media spec boolean field is unsupported: " + field);
        }
    }

    private static int requireInt(JsonNode node, String field, int minimum, int maximum) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid("media spec integer field is invalid: " + field);
        }
        int number = value.intValue();
        if (number < minimum || number > maximum) {
            throw invalid("media spec integer field is unsupported: " + field);
        }
        return number;
    }

    private static int roundHalfUp(long numerator, long denominator) {
        return Math.toIntExact((2 * numerator + denominator) / (2 * denominator));
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
