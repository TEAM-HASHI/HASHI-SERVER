package org.sopt.hashi.media.service;

import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.sopt.hashi.media.domain.ImageAsset;
import org.sopt.hashi.media.domain.ImageAssetRepository;
import org.sopt.hashi.media.domain.ImageRenditionRepository;
import org.sopt.hashi.media.domain.ImageRole;
import org.sopt.hashi.media.domain.MediaCleanupStatus;
import org.sopt.hashi.media.domain.TargetProcessingStatus;
import org.sopt.hashi.media.internal.reconciliation.MediaObjectLocation;
import org.sopt.hashi.media.internal.reconciliation.MediaObjectVersion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MediaReconciliationTransactionService {

    private static final Pattern ORIGINAL_KEY = Pattern.compile(
            "^media/originals/([0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})/original$");
    private static final Pattern RENDITION_KEY = Pattern.compile(
            "^media/renditions/([0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})/v([1-9][0-9]*)/([a-z0-9-]+)/([1-9][0-9]*)\\.webp$");
    private static final Set<String> ROLE_SEGMENTS = Arrays.stream(ImageRole.values())
            .map(role -> role.name().toLowerCase(Locale.ROOT).replace('_', '-'))
            .collect(Collectors.toUnmodifiableSet());

    private final ImageAssetRepository assetRepository;
    private final ImageRenditionRepository renditionRepository;

    public MediaReconciliationTransactionService(ImageAssetRepository assetRepository,
                                                 ImageRenditionRepository renditionRepository) {
        this.assetRepository = assetRepository;
        this.renditionRepository = renditionRepository;
    }

    /**
     * S3 후보 발견 뒤 삭제 직전에 호출한다. row lock은 이 메서드가 끝날 때 해제되며,
     * 호출자는 transaction이 종료된 뒤에만 S3 delete를 실행한다.
     */
    @Transactional
    public MediaReconciliationDecision assess(MediaObjectVersion object, Instant eligibleBefore) {
        if (object.lastModified().isAfter(eligibleBefore)) {
            return MediaReconciliationDecision.PROTECT;
        }
        Optional<ParsedObjectKey> parsed = parse(object);
        if (parsed.isEmpty()) {
            return MediaReconciliationDecision.UNKNOWN;
        }
        ParsedObjectKey key = parsed.get();
        Optional<ImageAsset> optionalAsset = assetRepository.findByPublicIdForUpdate(key.assetId());
        if (optionalAsset.isEmpty()) {
            return MediaReconciliationDecision.DELETE;
        }
        ImageAsset asset = optionalAsset.get();
        if (asset.getCleanupStatus() == MediaCleanupStatus.PURGING) {
            return MediaReconciliationDecision.PROTECT;
        }
        if (asset.getCleanupStatus() == MediaCleanupStatus.PURGED) {
            return MediaReconciliationDecision.DELETE;
        }
        return object.location() == MediaObjectLocation.ORIGINAL
                ? assessOriginal(object, asset)
                : assessRendition(key, asset);
    }

    private MediaReconciliationDecision assessOriginal(MediaObjectVersion object, ImageAsset asset) {
        if (!asset.getOriginalObjectKey().equals(object.objectKey())) {
            return MediaReconciliationDecision.UNKNOWN;
        }
        // complete가 아직 exact version을 고정하지 않은 upload/copy는 asset 전체 cleanup이 담당한다.
        if (asset.getSourceVersionId() == null) {
            return MediaReconciliationDecision.PROTECT;
        }
        return asset.getSourceVersionId().equals(object.versionId())
                ? MediaReconciliationDecision.PROTECT : MediaReconciliationDecision.DELETE;
    }

    private MediaReconciliationDecision assessRendition(ParsedObjectKey key, ImageAsset asset) {
        int specVersion = key.specVersion();
        if (Integer.valueOf(specVersion).equals(asset.getActiveSpecVersion())) {
            return MediaReconciliationDecision.PROTECT;
        }
        boolean currentTarget = asset.getTargetProcessingStatus() == TargetProcessingStatus.PROCESSING
                && Integer.valueOf(specVersion).equals(asset.getTargetSpecVersion());
        if (currentTarget) {
            return MediaReconciliationDecision.PROTECT;
        }
        if (renditionRepository.existsByImageAssetIdAndSpecVersion(asset.getId(), specVersion)) {
            // 과거 active spec은 exact manifest만이 아니라 같은 spec prefix 전체를 보존한다.
            return MediaReconciliationDecision.PROTECT;
        }
        Integer lastIssued = asset.getLastIssuedSpecVersion();
        if (lastIssued == null || specVersion > lastIssued) {
            // DB가 발급했다고 증명할 수 없는 미래 spec 경로는 자동 삭제하지 않는다.
            return MediaReconciliationDecision.UNKNOWN;
        }
        return MediaReconciliationDecision.DELETE;
    }

    private Optional<ParsedObjectKey> parse(MediaObjectVersion object) {
        Matcher matcher = (object.location() == MediaObjectLocation.ORIGINAL ? ORIGINAL_KEY : RENDITION_KEY)
                .matcher(object.objectKey());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            UUID assetId = UUID.fromString(matcher.group(1));
            if (object.location() == MediaObjectLocation.ORIGINAL) {
                return Optional.of(new ParsedObjectKey(assetId, 0));
            }
            int specVersion = Integer.parseInt(matcher.group(2));
            int width = Integer.parseInt(matcher.group(4));
            if (specVersion < 1 || width < 1 || !ROLE_SEGMENTS.contains(matcher.group(3))) {
                return Optional.empty();
            }
            return Optional.of(new ParsedObjectKey(assetId, specVersion));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private record ParsedObjectKey(UUID assetId, int specVersion) {
    }
}
