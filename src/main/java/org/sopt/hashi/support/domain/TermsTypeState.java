package org.sopt.hashi.support.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.sopt.hashi.support.TermsType;

@Getter
@Entity
@Table(name = "support_terms_type")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TermsTypeState {
    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 40)
    private TermsType type;

    @Column(name = "current_version_id")
    private Long currentVersionId;

    public void publish(Long versionId) { currentVersionId = versionId; }
}
