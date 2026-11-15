package org.sopt.hashi.media;

/** 조회된 이미지와 전환기 참조 값에서 화면에 전달할 값을 선택한다. DB 조회는 하지 않는다. */
public record MediaImageSelection(String url, MediaImage image, String legacyUrl) {

    public static MediaImageSelection from(ImageReference reference, MediaImage image) {
        if (reference == null) {
            return new MediaImageSelection(null, null, null);
        }
        if (reference.assetId() == null) {
            return new MediaImageSelection(reference.legacyUrl(), null, reference.legacyUrl());
        }
        if (image == null || !reference.assetId().equals(image.assetId())) {
            return new MediaImageSelection(null, null, null);
        }
        String url = image.status() == MediaImageStatus.READY ? image.defaultSource().url() : null;
        return new MediaImageSelection(url, image, null);
    }
}
