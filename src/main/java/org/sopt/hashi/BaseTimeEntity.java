package org.sopt.hashi;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import java.time.LocalDateTime;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 모든 엔티티가 상속하는 공통 감사(auditing) 필드. 생성/수정 시각을 JPA Auditing이 자동으로 채운다.
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseTimeEntity {

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    /** 컬렉션만 변경되어 JPA의 엔티티 변경 감지가 일어나지 않을 때 수정 시각을 기록한다. */
    protected void markUpdatedAt(LocalDateTime now) {
        this.updatedAt = now;
    }
}
