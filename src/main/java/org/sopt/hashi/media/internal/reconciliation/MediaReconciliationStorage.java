package org.sopt.hashi.media.internal.reconciliation;

public interface MediaReconciliationStorage extends AutoCloseable {

    MediaObjectVersionPage listObjectVersions(
            MediaObjectLocation location,
            MediaObjectVersionCursor cursor,
            int pageSize
    );

    void deleteObjectVersion(MediaObjectVersion object);

    @Override
    void close();
}
