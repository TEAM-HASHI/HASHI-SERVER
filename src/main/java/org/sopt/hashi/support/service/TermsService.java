package org.sopt.hashi.support.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.support.TermsCommand;
import org.sopt.hashi.support.TermsInfo;
import org.sopt.hashi.support.TermsSummaryInfo;
import org.sopt.hashi.support.TermsType;
import org.sopt.hashi.support.TermsTypeInfo;
import org.sopt.hashi.support.code.SupportErrorCode;
import org.sopt.hashi.support.domain.TermsTypeState;
import org.sopt.hashi.support.domain.TermsTypeStateRepository;
import org.sopt.hashi.support.domain.TermsVersion;
import org.sopt.hashi.support.domain.TermsVersionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class TermsService {
    private final TermsVersionRepository versions;
    private final TermsTypeStateRepository states;
    private final TermsContentCodec codec;
    private final Clock clock;

    public TermsService(TermsVersionRepository versions, TermsTypeStateRepository states,
                        TermsContentCodec codec, Clock clock) {
        this.versions = versions;
        this.states = states;
        this.codec = codec;
        this.clock = clock;
    }

    public List<TermsSummaryInfo> currentList() {
        return versions.findCurrent().stream().sorted(Comparator.comparing(v -> v.getType().ordinal()))
                .map(v -> new TermsSummaryInfo(v.getId(), v.getType(), v.getTitle(), v.getVersion(),
                        v.getEffectiveDate())).toList();
    }

    public TermsInfo currentDetail(Long id) {
        // 단일 join 조회로 current pointer와 본문을 같은 statement snapshot에서 확인한다.
        return versions.findCurrentById(id)
                .map(v -> info(v, id)).orElseThrow(this::notFound);
    }

    public List<TermsTypeInfo> adminTypes() {
        java.util.Map<TermsType, Long> byType = new java.util.EnumMap<>(TermsType.class);
        states.findAll().forEach(s -> byType.put(s.getType(), s.getCurrentVersionId()));
        return Arrays.stream(TermsType.values())
                .map(type -> new TermsTypeInfo(type, type.title(), byType.get(type))).toList();
    }

    public TermsInfo adminDetail(Long id) {
        TermsVersion version = versions.findById(id).orElseThrow(this::notFound);
        return info(version, state(version.getType()).getCurrentVersionId());
    }

    public Page<TermsInfo> adminHistory(TermsType type, int page, int size) {
        if (type == null) { throw invalid(); }
        Long current = state(type).getCurrentVersionId();
        PageRequest pageable = PageRequest.of(Math.max(page, 0), size < 1 ? 20 : Math.min(size, 100));
        return versions.findHistory(type, pageable).map(v -> info(v, current));
    }

    @Transactional
    public TermsInfo create(TermsCommand command) {
        String json = codec.validateAndEncode(command);
        TermsTypeState state = locked(command.type());
        if (versions.findVersionForUpdate(command.type(), command.version()).isPresent()) { throw duplicate(); }
        return info(versions.saveAndFlush(new TermsVersion(command, json)), state.getCurrentVersionId());
    }

    @Transactional
    public TermsInfo update(Long id, TermsCommand command) {
        String json = codec.validateAndEncode(command);
        TermsType type = versions.findType(id).orElseThrow(this::notFound);
        TermsTypeState state = locked(type);
        TermsVersion version = versions.findForUpdate(id).orElseThrow(this::notFound);
        version.requireDraft();
        if (type != command.type()) { throw invalid(); }
        if (versions.findVersionForUpdate(type, command.version()).filter(v -> !v.getId().equals(id)).isPresent()) { throw duplicate(); }
        version.update(command, json);
        return info(version, state.getCurrentVersionId());
    }

    @Transactional
    public TermsInfo publish(Long id) {
        TermsType type = versions.findType(id).orElseThrow(this::notFound);
        TermsTypeState state = locked(type);
        TermsVersion version = versions.findForUpdate(id).orElseThrow(this::notFound);
        codec.validateAndEncode(new TermsCommand(type, version.getTitle(), version.getVersion(),
                version.getEffectiveDate(), codec.decode(version.getClausesJson())));
        version.publish(LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS));
        versions.flush();
        state.publish(id);
        states.flush();
        return info(version, id);
    }

    @Transactional
    public void delete(Long id) {
        TermsType type = versions.findType(id).orElseThrow(this::notFound);
        locked(type);
        TermsVersion version = versions.findForUpdate(id).orElseThrow(this::notFound);
        version.requireDraft();
        versions.delete(version);
    }

    private TermsTypeState locked(TermsType type) {
        return states.findForUpdate(type).orElseThrow(() -> new IllegalStateException("terms control row missing"));
    }

    private TermsTypeState state(TermsType type) {
        return states.findById(type).orElseThrow(() -> new IllegalStateException("terms control row missing"));
    }

    private TermsInfo info(TermsVersion v, Long current) {
        String status = v.getPublishedAt() == null ? "DRAFT"
                : Objects.equals(v.getId(), current) ? "CURRENT" : "ARCHIVED";
        return new TermsInfo(v.getId(), v.getType(), v.getTitle(), v.getVersion(), v.getEffectiveDate(),
                codec.decode(v.getClausesJson()), status, v.getPublishedAt());
    }

    private BusinessException notFound() { return new BusinessException(SupportErrorCode.TERMS_NOT_FOUND); }
    private BusinessException invalid() { return new BusinessException(SupportErrorCode.INVALID_TERMS); }
    private BusinessException duplicate() { return new BusinessException(SupportErrorCode.DUPLICATE_TERMS_VERSION); }
}
