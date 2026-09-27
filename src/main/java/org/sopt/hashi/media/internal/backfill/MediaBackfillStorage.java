package org.sopt.hashi.media.internal.backfill;

import java.util.UUID;

/** migration 전용 outbound port. 호출자는 DB transaction 밖에서만 사용한다. */
public interface MediaBackfillStorage extends AutoCloseable {

    LegacyImageSource inspectSource(String legacyKey);

    BackfillOriginalCopy findOrCopyOriginal(UUID assetId, String identityHash, LegacyImageSource source);

    @Override
    void close();
}
