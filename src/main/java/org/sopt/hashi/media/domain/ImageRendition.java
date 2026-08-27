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
}
