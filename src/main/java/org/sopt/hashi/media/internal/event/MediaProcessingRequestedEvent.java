package org.sopt.hashi.media.internal.event;

import java.util.UUID;

public record MediaProcessingRequestedEvent(UUID assetId, UUID jobId) {
}
