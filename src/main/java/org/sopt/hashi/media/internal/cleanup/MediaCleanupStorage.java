package org.sopt.hashi.media.internal.cleanup;

import java.util.UUID;

public interface MediaCleanupStorage extends AutoCloseable {

    /** DB에서 PURGING을 커밋한 asset만 넘긴다. bucket과 object key는 호출자가 지정하지 않는다. */
    MediaObjectPurgeResult purgeAssetObjects(UUID assetId);

    @Override
    void close();
}
