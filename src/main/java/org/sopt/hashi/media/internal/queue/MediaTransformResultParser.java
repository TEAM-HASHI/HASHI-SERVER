package org.sopt.hashi.media.internal.queue;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.sopt.hashi.media.domain.ImageFormat;
import org.sopt.hashi.media.domain.ImageRole;
import org.springframework.stereotype.Component;

@Component
public class MediaTransformResultParser {

    private static final int CURRENT_CONTRACT_VERSION = 1;
    private static final int MAX_SOURCE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_SOURCE_DIMENSION = 10_000;
    private static final long MAX_SOURCE_PIXELS = 40_000_000L;
    private static final int MAX_RENDITIONS = 64;
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    private static final Pattern SPEC_DIGEST_PATTERN = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern SOURCE_CHECKSUM_PATTERN =
            Pattern.compile("^[A-Za-z0-9+/]{43}=$");
    private static final Set<String> SOURCE_MIME_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp");
    private static final Set<String> SUCCESS_KEYS = Set.of(
            "contractVersion", "jobId", "assetId", "specVersion", "specDigest", "status",
            "sourceVersionId", "sourceETag", "verifiedSource", "renditions");
    private static final Set<String> FAILED_KEYS = Set.of(
            "contractVersion", "jobId", "assetId", "specVersion", "specDigest", "status",
            "sourceVersionId", "sourceETag", "failureCode");
    private static final Set<String> VERIFIED_SOURCE_KEYS = Set.of(
            "mimeType", "byteSize", "width", "height", "checksumSha256");
    private static final Set<String> RENDITION_KEYS = Set.of(
            "role", "format", "width", "height", "byteSize", "objectKey");

    private final ObjectMapper objectMapper;

    public MediaTransformResultParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public MediaTransformResult parse(String body) {
        JsonNode root;
        try (JsonParser jsonParser = objectMapper.createParser(body)) {
            jsonParser.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature());
            root = objectMapper.readTree(jsonParser);
            if (jsonParser.nextToken() != null) {
                throw new MediaTransformContractException(
                        "media result contains trailing JSON content");
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new MediaTransformContractException("media result is not valid JSON");
        }
        requireObject(root, "media result must be an object");
        String status = requireText(root, "status", 1, 20);
        return switch (status) {
            case "SUCCEEDED" -> parseSucceeded(root);
            case "FAILED" -> parseFailed(root);
            default -> throw new MediaTransformContractException(
                    "media result status is unsupported");
        };
    }

    private MediaTransformSucceededResult parseSucceeded(JsonNode root) {
        requireExactKeys(root, SUCCESS_KEYS, "media success result fields do not match contract v1");
        Identity identity = parseIdentity(root);
        MediaVerifiedSource source = parseVerifiedSource(root.path("verifiedSource"));
        JsonNode renditionsNode = root.path("renditions");
        if (!renditionsNode.isArray()
                || renditionsNode.isEmpty()
                || renditionsNode.size() > MAX_RENDITIONS) {
            throw new MediaTransformContractException("media result renditions are invalid");
        }
        List<MediaRenditionResult> renditions = new ArrayList<>();
        renditionsNode.forEach(node -> renditions.add(parseRendition(node)));
        return new MediaTransformSucceededResult(
                identity.contractVersion(),
                identity.jobId(),
                identity.assetId(),
                identity.specVersion(),
                identity.specDigest(),
                identity.sourceVersionId(),
                identity.sourceETag(),
                source,
                renditions
        );
    }

    private MediaTransformFailedResult parseFailed(JsonNode root) {
        requireExactKeys(root, FAILED_KEYS, "media failed result fields do not match contract v1");
        Identity identity = parseIdentity(root);
        MediaTransformFailureCode failureCode = requireEnum(
                root, "failureCode", MediaTransformFailureCode.class,
                "media result failureCode is invalid");
        return new MediaTransformFailedResult(
                identity.contractVersion(),
                identity.jobId(),
                identity.assetId(),
                identity.specVersion(),
                identity.specDigest(),
                identity.sourceVersionId(),
                identity.sourceETag(),
                failureCode
        );
    }

    private Identity parseIdentity(JsonNode root) {
        int contractVersion = requireInt(root, "contractVersion", 1, Integer.MAX_VALUE);
        if (contractVersion != CURRENT_CONTRACT_VERSION) {
            throw new MediaTransformContractException(
                    "media result contractVersion is unsupported");
        }
        return new Identity(
                contractVersion,
                requireUuid(root, "jobId"),
                requireUuid(root, "assetId"),
                requireInt(root, "specVersion", 1, Integer.MAX_VALUE),
                requirePattern(root, "specDigest", SPEC_DIGEST_PATTERN,
                        "media result specDigest is invalid"),
                requireText(root, "sourceVersionId", 1, 1_024),
                requireText(root, "sourceETag", 1, 255)
        );
    }

    private MediaVerifiedSource parseVerifiedSource(JsonNode node) {
        requireObject(node, "media result verifiedSource must be an object");
        requireExactKeys(
                node, VERIFIED_SOURCE_KEYS,
                "media result verifiedSource fields do not match contract v1");
        String mimeType = requireText(node, "mimeType", 1, 50);
        if (!SOURCE_MIME_TYPES.contains(mimeType)) {
            throw new MediaTransformContractException(
                    "media result verifiedSource mimeType is invalid");
        }
        long byteSize = requireLong(node, "byteSize", 1, MAX_SOURCE_BYTES);
        int width = requireInt(node, "width", 1, MAX_SOURCE_DIMENSION);
        int height = requireInt(node, "height", 1, MAX_SOURCE_DIMENSION);
        if ((long) width * height > MAX_SOURCE_PIXELS) {
            throw new MediaTransformContractException(
                    "media result verifiedSource pixel count is invalid");
        }
        String checksum = requirePattern(
                node, "checksumSha256", SOURCE_CHECKSUM_PATTERN,
                "media result verifiedSource checksum is invalid");
        return new MediaVerifiedSource(mimeType, byteSize, width, height, checksum);
    }

    private MediaRenditionResult parseRendition(JsonNode node) {
        requireObject(node, "media result rendition must be an object");
        requireExactKeys(node, RENDITION_KEYS,
                "media result rendition fields do not match contract v1");
        return new MediaRenditionResult(
                requireEnum(node, "role", ImageRole.class,
                        "media result rendition role is invalid"),
                requireEnum(node, "format", ImageFormat.class,
                        "media result rendition format is invalid"),
                requireInt(node, "width", 1, MAX_SOURCE_DIMENSION),
                requireInt(node, "height", 1, MAX_SOURCE_DIMENSION),
                requireLong(node, "byteSize", 1, Long.MAX_VALUE),
                requireText(node, "objectKey", 1, 500)
        );
    }

    private void requireObject(JsonNode node, String message) {
        if (node == null || !node.isObject()) {
            throw new MediaTransformContractException(message);
        }
    }

    private void requireExactKeys(JsonNode node, Set<String> expected, String message) {
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw new MediaTransformContractException(message);
        }
    }

    private UUID requireUuid(JsonNode node, String fieldName) {
        String value = requireText(node, fieldName, 36, 36);
        if (!UUID_PATTERN.matcher(value).matches()) {
            throw new MediaTransformContractException(
                    "media result identifier is invalid");
        }
        try {
            UUID uuid = UUID.fromString(value);
            if (!uuid.toString().equals(value)) {
                throw new IllegalArgumentException("UUID is not canonical");
            }
            return uuid;
        } catch (IllegalArgumentException e) {
            throw new MediaTransformContractException(
                    "media result identifier is invalid");
        }
    }

    private String requirePattern(JsonNode node, String fieldName, Pattern pattern, String message) {
        String value = requireText(node, fieldName, 1, 1_024);
        if (!pattern.matcher(value).matches()) {
            throw new MediaTransformContractException(message);
        }
        return value;
    }

    private String requireText(JsonNode node, String fieldName, int minimum, int maximum) {
        JsonNode value = node.get(fieldName);
        if (value == null || !value.isTextual()
                || value.textValue().length() < minimum
                || value.textValue().length() > maximum) {
            throw new MediaTransformContractException(
                    "media result text field is invalid: " + fieldName);
        }
        return value.textValue();
    }

    private int requireInt(JsonNode node, String fieldName, int minimum, int maximum) {
        JsonNode value = node.get(fieldName);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new MediaTransformContractException(
                    "media result integer field is invalid: " + fieldName);
        }
        int number = value.intValue();
        if (number < minimum || number > maximum) {
            throw new MediaTransformContractException(
                    "media result integer field is invalid: " + fieldName);
        }
        return number;
    }

    private long requireLong(JsonNode node, String fieldName, long minimum, long maximum) {
        JsonNode value = node.get(fieldName);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new MediaTransformContractException(
                    "media result long field is invalid: " + fieldName);
        }
        long number = value.longValue();
        if (number < minimum || number > maximum) {
            throw new MediaTransformContractException(
                    "media result long field is invalid: " + fieldName);
        }
        return number;
    }

    private <E extends Enum<E>> E requireEnum(
            JsonNode node, String fieldName, Class<E> type, String message) {
        String value = requireText(node, fieldName, 1, 64);
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new MediaTransformContractException(message);
        }
    }

    private record Identity(
            int contractVersion,
            UUID jobId,
            UUID assetId,
            int specVersion,
            String specDigest,
            String sourceVersionId,
            String sourceETag
    ) {
    }
}
