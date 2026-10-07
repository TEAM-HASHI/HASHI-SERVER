package org.sopt.hashi.support.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.sopt.hashi.media.MediaAssetPurpose;
import org.sopt.hashi.media.MediaAssetUse;
import org.sopt.hashi.media.MediaImage;
import org.sopt.hashi.media.MediaImageRequest;
import org.sopt.hashi.media.MediaImageRole;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.support.NoticeCommand;
import org.sopt.hashi.support.NoticeInfo;
import org.sopt.hashi.support.code.SupportErrorCode;
import org.sopt.hashi.support.domain.Notice;
import org.sopt.hashi.support.domain.NoticeCursor;
import org.sopt.hashi.support.domain.NoticeRepository;
import org.sopt.hashi.support.dto.NoticeListResponse;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class NoticeService {
    private final NoticeRepository repository;
    private final NoticeContentCodec codec;
    private final MediaPort mediaPort;
    private final Clock clock;

    public NoticeService(NoticeRepository repository, NoticeContentCodec codec, MediaPort mediaPort, Clock clock) {
        this.repository = repository;
        this.codec = codec;
        this.mediaPort = mediaPort;
        this.clock = clock;
    }

    public NoticeListResponse list(String cursorValue) {
        NoticeCursor cursor = NoticeCursor.parse(cursorValue);
        List<Notice> found = repository.findPublished(cursor.publishedAt(), cursor.id(), Limit.of(11));
        boolean hasNext = found.size() > 10;
        List<Notice> page = found.subList(0, Math.min(10, found.size()));
        String next = hasNext ? new NoticeCursor(page.getLast().getPublishedAt(), page.getLast().getId()).encode() : null;
        return new NoticeListResponse(page.stream()
                .map(n -> new NoticeListResponse.Item(n.getId(), n.getTitle(), n.lastModifiedAt())).toList(),
                next, hasNext);
    }

    public NoticeInfo detail(Long id) {
        Notice notice = repository.findByIdAndDeletedFalseAndPublishedAtIsNotNull(id)
                .orElseThrow(this::notFound);
        return info(notice);
    }

    public NoticeInfo adminDetail(Long id) {
        return info(repository.findByIdAndDeletedFalse(id).orElseThrow(this::notFound));
    }

    public Page<NoticeInfo> adminList(int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), size < 1 ? 20 : Math.min(size, 100));
        Page<Notice> notices = repository.findAdminPage(pageable);
        return new PageImpl<>(infos(notices.getContent()), pageable, notices.getTotalElements());
    }

    @Transactional
    public NoticeInfo create(NoticeCommand command) {
        String json = codec.validateAndEncode(command);
        reconcile(command.imageAssetIds(), List.of());
        return info(repository.save(new Notice(command.title(), json, command.imageAssetIds())));
    }

    @Transactional
    public NoticeInfo update(Long id, NoticeCommand command) {
        String json = codec.validateAndEncode(command);
        Notice notice = locked(id);
        List<UUID> old = List.copyOf(notice.getImageAssetIds());
        reconcile(command.imageAssetIds().stream().filter(asset -> !old.contains(asset)).toList(),
                old.stream().filter(asset -> !command.imageAssetIds().contains(asset)).toList());
        // 순서 교체에서 image_asset_id unique 충돌을 피한다. 두 flush는 동일 transaction이다.
        notice.clearImages();
        repository.flush();
        notice.update(command.title(), json, command.imageAssetIds(), now(), !old.equals(command.imageAssetIds()));
        repository.flush();
        return info(notice);
    }

    @Transactional
    public NoticeInfo publish(Long id) {
        Notice notice = locked(id);
        // 기존 초안을 재검증해 게시 시에도 입력 계약을 유지한다.
        codec.validateAndEncode(new NoticeCommand(notice.getTitle(), codec.decode(notice.getBodyJson()),
                List.copyOf(notice.getImageAssetIds())));
        notice.publish(now());
        repository.flush();
        return info(notice);
    }

    @Transactional
    public void delete(Long id) {
        Notice notice = locked(id);
        reconcile(List.of(), List.copyOf(notice.getImageAssetIds()));
        notice.delete();
    }

    private Notice locked(Long id) {
        return repository.findForUpdate(id).orElseThrow(this::notFound);
    }

    private BusinessException notFound() {
        return new BusinessException(SupportErrorCode.NOTICE_NOT_FOUND);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private void reconcile(List<UUID> claims, List<UUID> retires) {
        if (!claims.isEmpty() || !retires.isEmpty()) {
            mediaPort.reconcileBindings(
                    claims.stream().map(id -> new MediaAssetUse(id, MediaAssetPurpose.NOTICE)).toList(),
                    retires.stream().map(id -> new MediaAssetUse(id, MediaAssetPurpose.NOTICE)).toList());
        }
    }

    private NoticeInfo info(Notice notice) {
        return infos(List.of(notice)).getFirst();
    }

    private List<NoticeInfo> infos(List<Notice> notices) {
        List<MediaImageRequest> requests = notices.stream().flatMap(n -> n.getImageAssetIds().stream())
                .distinct().map(id -> new MediaImageRequest(id, MediaImageRole.NOTICE_DETAIL)).toList();
        Map<MediaImageRequest, MediaImage> images = requests.isEmpty() ? Map.of() : mediaPort.findImages(requests);
        return notices.stream().map(n -> new NoticeInfo(n.getId(), n.getTitle(), codec.decode(n.getBodyJson()),
                n.getPublishedAt() == null ? "DRAFT" : "PUBLISHED", n.getPublishedAt(), n.lastModifiedAt(),
                n.getImageAssetIds().stream().map(id -> new NoticeInfo.Attachment(id,
                        images.get(new MediaImageRequest(id, MediaImageRole.NOTICE_DETAIL)))).toList(),
                n.getCreatedAt(), n.getUpdatedAt())).toList();
    }
}
