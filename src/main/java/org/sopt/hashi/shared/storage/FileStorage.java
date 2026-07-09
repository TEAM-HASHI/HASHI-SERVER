package org.sopt.hashi.shared.storage;

public interface FileStorage {

    PresignedUploadInfo createPresignedUploadUrl(String fileKey, String contentType, long contentLength);

    String resolveFileUrl(String fileKey);
}
