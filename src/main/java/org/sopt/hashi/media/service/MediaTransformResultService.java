package org.sopt.hashi.media.service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.sopt.hashi.media.domain.ImageAsset;
import org.sopt.hashi.media.domain.ImageAssetRepository;
import org.sopt.hashi.media.domain.ImageFormat;
import org.sopt.hashi.media.internal.queue.MediaRenditionResult;
import org.sopt.hashi.media.internal.queue.MediaTransformContractException;
import org.sopt.hashi.media.internal.queue.MediaTransformFailedResult;
import org.sopt.hashi.media.internal.queue.MediaTransformResult;
import org.sopt.hashi.media.internal.queue.MediaTransformSucceededResult;
import org.sopt.hashi.media.internal.queue.MediaVerifiedSource;
import org.sopt.hashi.media.internal.spec.MediaExpectedRendition;
import org.sopt.hashi.media.internal.spec.MediaSpecDefinition;
import org.sopt.hashi.media.internal.spec.MediaSpecRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MediaTransformResultService {

    private final ImageAssetRepository imageAssetRepository;
    private final MediaSpecRegistry mediaSpecRegistry;

    public MediaTransformResultService(ImageAssetRepository imageAssetRepository,
                                       MediaSpecRegistry mediaSpecRegistry) {
        this.imageAssetRepository = imageAssetRepository;
        this.mediaSpecRegistry = mediaSpecRegistry;
    }

    @Transactional
    public MediaTransformResultDisposition apply(MediaTransformResult result) {
        Optional<ImageAsset> optionalAsset =
                imageAssetRepository.findByPublicIdForUpdate(result.assetId());
        if (optionalAsset.isEmpty()) {
            return MediaTransformResultDisposition.STALE;
        }

        ImageAsset asset = optionalAsset.get();
        if (!asset.matchesCurrentProcessingAttempt(
                result.jobId(), result.sourceVersionId(), result.sourceETag())) {
            return MediaTransformResultDisposition.STALE;
        }
        if (!asset.matchesTargetSpec(result.specVersion(), result.specDigest())) {
            throw contractMismatch("media result target spec differs from the current job");
        }

        MediaSpecDefinition spec = mediaSpecRegistry.findDefinition(result.specVersion())
                .orElseThrow(() -> contractMismatch(
                        "media result references an unknown spec version"));
        if (!spec.digest().equals(result.specDigest())) {
            throw contractMismatch("media result spec digest differs from the packaged manifest");
        }

        if (result instanceof MediaTransformFailedResult failed) {
            asset.failCurrentProcessing(
                    failed.jobId(),
                    failed.specVersion(),
                    failed.specDigest(),
                    failed.failureCode().name()
            );
            return MediaTransformResultDisposition.APPLIED;
        }

        MediaTransformSucceededResult succeeded = (MediaTransformSucceededResult) result;
        validateVerifiedSource(asset, succeeded.verifiedSource());
        validateRenditions(asset, spec, succeeded);
        succeeded.renditions().forEach(rendition -> asset.addRendition(
                succeeded.jobId(),
                succeeded.specVersion(),
                succeeded.specDigest(),
                rendition.role(),
                rendition.format(),
                rendition.width(),
                rendition.height(),
                rendition.byteSize(),
                rendition.objectKey()
        ));
        MediaVerifiedSource source = succeeded.verifiedSource();
        asset.completeCurrentProcessing(
                succeeded.jobId(),
                succeeded.specVersion(),
                succeeded.specDigest(),
                source.mimeType(),
                source.byteSize(),
                source.width(),
                source.height(),
                source.checksumSha256()
        );
        return MediaTransformResultDisposition.APPLIED;
    }

    private void validateVerifiedSource(ImageAsset asset, MediaVerifiedSource source) {
        if (!asset.getDeclaredContentType().equalsIgnoreCase(source.mimeType())
                || asset.getDeclaredBytes() != source.byteSize()) {
            throw contractMismatch(
                    "media result verified source differs from the upload declaration");
        }
        if (asset.getActualContentType() != null) {
            boolean matchesExisting = asset.getActualContentType().equals(source.mimeType())
                    && asset.getActualBytes() == source.byteSize()
                    && asset.getSourceWidth() == source.width()
                    && asset.getSourceHeight() == source.height()
                    && asset.getSourceChecksumSha256().equals(source.checksumSha256());
            if (!matchesExisting) {
                throw contractMismatch("media result verified source identity has changed");
            }
        }
    }

    private void validateRenditions(ImageAsset asset, MediaSpecDefinition spec,
                                    MediaTransformSucceededResult succeeded) {
        List<MediaExpectedRendition> expected;
        try {
            expected = spec.expectedRenditions(
                    asset.getPurpose(),
                    succeeded.verifiedSource().width(),
                    succeeded.verifiedSource().height()
            );
        } catch (IllegalArgumentException e) {
            throw contractMismatch("media result source cannot satisfy the packaged manifest");
        }
        List<MediaExpectedRendition> actual = succeeded.renditions().stream()
                .map(rendition -> new MediaExpectedRendition(
                        rendition.role(), rendition.width(), rendition.height()))
                .toList();
        if (!actual.equals(expected)) {
            throw contractMismatch("media result renditions differ from the packaged manifest");
        }

        for (MediaRenditionResult rendition : succeeded.renditions()) {
            if (rendition.format() != ImageFormat.WEBP) {
                throw contractMismatch("media result rendition format is unsupported");
            }
            String expectedKey = renditionObjectKey(
                    asset, succeeded.specVersion(), rendition);
            if (!expectedKey.equals(rendition.objectKey())) {
                throw contractMismatch("media result rendition object key is invalid");
            }
        }
    }

    private String renditionObjectKey(ImageAsset asset, int specVersion,
                                      MediaRenditionResult rendition) {
        String roleSegment = rendition.role().name()
                .toLowerCase(Locale.ROOT)
                .replace('_', '-');
        return "media/renditions/%s/v%d/%s/%d.webp".formatted(
                asset.getPublicId(), specVersion, roleSegment, rendition.width());
    }

    private MediaTransformContractException contractMismatch(String message) {
        return new MediaTransformContractException(message);
    }
}
