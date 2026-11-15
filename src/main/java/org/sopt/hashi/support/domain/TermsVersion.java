package org.sopt.hashi.support.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.support.TermsCommand;
import org.sopt.hashi.support.TermsType;
import org.sopt.hashi.support.code.SupportErrorCode;

@Getter
@Entity
@Table(name = "support_terms_version")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TermsVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40, updatable = false)
    private TermsType type;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, length = 50)
    private String version;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Column(name = "clauses_json", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String clausesJson;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    public TermsVersion(TermsCommand command, String clausesJson) {
        this.type = command.type();
        apply(command, clausesJson);
    }

    public void update(TermsCommand command, String clausesJson) {
        requireDraft();
        if (type != command.type()) {
            throw new BusinessException(SupportErrorCode.INVALID_TERMS);
        }
        apply(command, clausesJson);
    }

    public void publish(LocalDateTime now) {
        requireDraft();
        publishedAt = now;
    }

    public void requireDraft() {
        if (publishedAt != null) {
            throw new BusinessException(SupportErrorCode.TERMS_IMMUTABLE);
        }
    }

    private void apply(TermsCommand command, String clausesJson) {
        title = command.title();
        version = command.version();
        effectiveDate = command.effectiveDate();
        this.clausesJson = clausesJson;
    }
}
