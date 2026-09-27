package org.sopt.hashi.media.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.sopt.hashi.BaseTimeEntity;

@Getter
@Entity
@Table(name = "image_rendition")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ImageRendition extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "image_asset_id", nullable = false, updatable = false)
    private ImageAsset imageAsset;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 40, nullable = false, updatable = false)
    private ImageRole role;

    @Column(name = "spec_version", nullable = false, updatable = false)
    private int specVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "format", length = 20, nullable = false, updatable = false)
    private ImageFormat format;

    @Column(name = "mime_type", length = 50, nullable = false, updatable = false)
    private String mimeType;

    @Column(name = "width", nullable = false, updatable = false)
    private int width;

    @Column(name = "height", nullable = false, updatable = false)
    private int height;

    @Column(name = "bytes", nullable = false, updatable = false)
    private long bytes;

    @Column(name = "object_key", length = 500, nullable = false, updatable = false, unique = true)
    private String objectKey;

    static ImageRendition create(ImageAsset imageAsset, ImageRole role, int specVersion,
                                 ImageFormat format, int width, int height, long bytes,
                                 String objectKey) {
        if (specVersion < 1 || width < 1 || height < 1 || bytes < 1) {
            throw new IllegalArgumentException("rendition values must be positive");
        }
        ImageRendition rendition = new ImageRendition();
        rendition.imageAsset = Objects.requireNonNull(imageAsset);
        rendition.role = Objects.requireNonNull(role);
        rendition.specVersion = specVersion;
        rendition.format = Objects.requireNonNull(format);
        rendition.mimeType = switch (format) {
            case WEBP -> "image/webp";
        };
        rendition.width = width;
        rendition.height = height;
        rendition.bytes = bytes;
        rendition.objectKey = requireText(objectKey, "objectKey");
        return rendition;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
