package org.sopt.hashi.media.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Getter
@Entity
@Table(name = "media_pipeline_config")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MediaPipelineConfig {

    public static final int SINGLETON_ID = 1;

    @Id
    @JdbcTypeCode(SqlTypes.TINYINT)
    private Integer id;

    @Column(name = "current_spec_version", nullable = false)
    private int currentSpecVersion;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "current_spec_digest", length = 64, nullable = false)
    private String currentSpecDigest;

    @Column(name = "issuance_enabled", nullable = false)
    private boolean issuanceEnabled;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public boolean matches(int specVersion, String specDigest) {
        return currentSpecVersion == specVersion && currentSpecDigest.equals(specDigest);
    }
}
