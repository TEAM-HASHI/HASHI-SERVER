package org.sopt.hashi.media.internal.storage;

import java.util.Optional;

public interface MediaOriginalStorage extends AutoCloseable {

    PresignedOriginalUpload createPresignedUpload(
            String objectKey,
            String contentType,
            long contentLength
    );

    Optional<OriginalObjectMetadata> findObjectMetadata(String objectKey);

    @Override
    void close();
}
