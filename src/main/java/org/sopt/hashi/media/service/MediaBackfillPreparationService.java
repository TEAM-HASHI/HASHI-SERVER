package org.sopt.hashi.media.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import org.sopt.hashi.media.MediaAssetPurpose;
import org.sopt.hashi.media.MediaBackfillAssetInfo;
import org.sopt.hashi.media.MediaBackfillAssetInfo.State;
import org.sopt.hashi.media.MediaBackfillInspectionInfo;
import org.sopt.hashi.media.MediaBackfillReference;
import org.sopt.hashi.media.MediaBackfillSourceException;
import org.sopt.hashi.media.MediaBackfillSourceException.Reason;
import org.sopt.hashi.media.MediaBackfillTarget;
import org.sopt.hashi.media.code.MediaErrorCode;
import org.sopt.hashi.media.domain.ImageBindingStatus;
import org.sopt.hashi.media.domain.MediaCleanupStatus;
import org.sopt.hashi.media.domain.MediaPurpose;
import org.sopt.hashi.media.internal.backfill.BackfillOriginalCopy;
import org.sopt.hashi.media.internal.backfill.LegacyImageSource;
import org.sopt.hashi.media.internal.backfill.MediaBackfillIdentityFactory;
import org.sopt.hashi.media.internal.backfill.MediaBackfillProperties;
import org.sopt.hashi.media.internal.backfill.MediaBackfillStorage;
import org.sopt.hashi.media.internal.backfill.MediaBackfillStorageException;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class MediaBackfillPreparationService {

    private final MediaBackfillProperties properties;
    private final ObjectProvider<MediaBackfillStorage> storageProvider;
    private final MediaBackfillIdentityFactory identityFactory;
    private final MediaBackfillReservationService reservationService;
    private final MediaBackfillTransactionService transactionService;
    private final Clock clock;

    MediaBackfillPreparationService(MediaBackfillProperties properties,
                                   ObjectProvider<MediaBackfillStorage> storageProvider,
                                   MediaBackfillIdentityFactory identityFactory,
                                   MediaBackfillReservationService reservationService,
                                   MediaBackfillTransactionService transactionService,
                                   @Qualifier("japanClock") Clock clock) {
        this.properties = properties;
        this.storageProvider = storageProvider;
        this.identityFactory = identityFactory;
        this.reservationService = reservationService;
        this.transactionService = transactionService;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.NEVER)
    public MediaBackfillInspectionInfo inspect(MediaBackfillReference reference) {
        Objects.requireNonNull(reference, "reference is required");
        MediaBackfillStorage storage = requireStorage();
        try {
            LegacyImageSource source = storage.inspectSource(reference.legacyKey());
            String identity = identity(reference, source);
            Optional<MediaBackfillAssetInfo> existing = transactionService
                    .findReservation(purpose(reference), identity, source).map(this::toInfo);
            return new MediaBackfillInspectionInfo(identity, reference.target().purpose(), existing);
        } catch (MediaBackfillStorageException exception) {
            throw sourceFailure(exception);
        }
    }

    @Transactional(propagation = Propagation.NEVER)
    public MediaBackfillAssetInfo prepare(MediaBackfillReference reference, String expectedIdentityHash) {
        Objects.requireNonNull(reference, "reference is required");
        if (expectedIdentityHash == null || !expectedIdentityHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("expected identity must be a lowercase SHA-256 digest");
        }
        MediaBackfillStorage storage = requireStorage();
        try {
            LegacyImageSource source = storage.inspectSource(reference.legacyKey());
            String identity = identity(reference, source);
            if (!expectedIdentityHash.equals(identity)) {
                throw new MediaBackfillSourceException(Reason.SOURCE_CHANGED);
            }
            BackfillAssetSnapshot asset = reservationService.reserveOrReuse(purpose(reference), identity, source);
            MediaBackfillAssetInfo info = toInfo(asset);
            if (info.state() != State.PENDING_COPY) {
                return info;
            }
            // pause 중 불필요한 copy를 시작하지 않는다. copy 중 pause 경합은 completeCopy에서 다시 차단한다.
            transactionService.assertIssuanceAvailable();
            BackfillOriginalCopy copy = storage.findOrCopyOriginal(asset.assetId(), identity, source);
            return toInfo(transactionService.completeCopy(asset.assetId(), copy));
        } catch (MediaBackfillStorageException exception) {
            throw sourceFailure(exception);
        }
    }

    private MediaBackfillStorage requireStorage() {
        if (!properties.enabled()) {
            throw new BusinessException(MediaErrorCode.PIPELINE_UNAVAILABLE);
        }
        MediaBackfillStorage storage = storageProvider.getIfAvailable();
        if (storage == null) {
            throw new BusinessException(MediaErrorCode.PIPELINE_UNAVAILABLE);
        }
        return storage;
    }

    private String identity(MediaBackfillReference reference, LegacyImageSource source) {
        MediaBackfillTarget target = reference.target();
        return identityFactory.create(target.associationKind(), reference.associationId(), target.slot(), source);
    }

    private MediaPurpose purpose(MediaBackfillReference reference) {
        return MediaPurpose.valueOf(reference.target().purpose().name());
    }

    private MediaBackfillAssetInfo toInfo(BackfillAssetSnapshot asset) {
        return new MediaBackfillAssetInfo(asset.assetId(), MediaAssetPurpose.valueOf(asset.purpose().name()),
                asset.identityHash(), state(asset));
    }

    private State state(BackfillAssetSnapshot asset) {
        if (asset.cleanupStatus() == MediaCleanupStatus.PURGING) {
            return State.PURGING;
        }
        if (asset.cleanupStatus() == MediaCleanupStatus.PURGED) {
            return State.PURGED;
        }
        if (asset.bindingStatus() == ImageBindingStatus.BOUND) {
            return State.BOUND;
        }
        if (asset.bindingStatus() == ImageBindingStatus.RETIRED) {
            return State.RETIRED;
        }
        return switch (asset.processingStatus()) {
            case PENDING_UPLOAD -> LocalDateTime.now(clock).isBefore(asset.expiresAt())
                    ? State.PENDING_COPY : State.EXPIRED;
            case PROCESSING -> State.PROCESSING;
            case READY -> State.READY;
            case FAILED -> State.FAILED;
            case EXPIRED -> State.EXPIRED;
        };
    }

    private MediaBackfillSourceException sourceFailure(MediaBackfillStorageException exception) {
        Reason reason = switch (exception.getReason()) {
            case SOURCE_MISSING -> Reason.SOURCE_MISSING;
            case SOURCE_UNREADABLE -> Reason.SOURCE_UNREADABLE;
            case SOURCE_CHANGED -> Reason.SOURCE_CHANGED;
            case INVALID_SOURCE -> Reason.INVALID_SOURCE;
            case COPY_CONFLICT -> Reason.COPY_CONFLICT;
            case STORAGE_UNAVAILABLE -> Reason.STORAGE_UNAVAILABLE;
        };
        return new MediaBackfillSourceException(reason);
    }
}
