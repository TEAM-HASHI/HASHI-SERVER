package org.sopt.hashi.magazine.service;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.sopt.hashi.magazine.MagazineInfo;
import org.sopt.hashi.magazine.code.MagazineErrorCode;
import org.sopt.hashi.magazine.domain.Magazine;
import org.sopt.hashi.magazine.domain.MagazineRepository;
import org.sopt.hashi.magazine.dto.MagazineBannerListResponse;
import org.sopt.hashi.magazine.dto.MagazineBannerListResponse.MagazineBannerResponse;
import org.sopt.hashi.magazine.dto.MagazineListResponse;
import org.sopt.hashi.magazine.dto.MagazineListResponse.MagazineSummaryResponse;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.storage.FileStorage;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional(readOnly = true)
public class MagazineService {

    private static final int BANNER_COUNT = 5;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 50;

    private final MagazineRepository magazineRepository;
    private final FileStorage fileStorage;

    public MagazineService(MagazineRepository magazineRepository, FileStorage fileStorage) {
        this.magazineRepository = magazineRepository;
        this.fileStorage = fileStorage;
    }

    /** 매거진 배너 목록 — 최신 매거진 5개의 배너를 인스타그램 리다이렉트 URL과 함께 내린다. */
    public MagazineBannerListResponse getBanners() {
        List<Magazine> magazines = magazineRepository.findAllByOrderByIdDesc(
                PageRequest.of(0, BANNER_COUNT));
        return new MagazineBannerListResponse(magazines.stream()
                .map(this::toBannerResponse)
                .toList());
    }

    /** 매거진 목록 — 최신순 커서 페이지네이션. ⚠️ 필터링은 MVP 이후 추가 예정이라 받지 않는다. */
    public MagazineListResponse getMagazines(Long cursor, Integer size) {
        int pageSize = normalizeSize(size);
        List<Magazine> rows = fetchPage(cursor, PageRequest.of(0, pageSize + 1));

        boolean hasNext = rows.size() > pageSize;
        List<Magazine> pageContent = hasNext ? rows.subList(0, pageSize) : rows;
        Long nextCursor = hasNext ? pageContent.getLast().getId() : null;

        return new MagazineListResponse(
                pageContent.stream()
                        .map(this::toSummaryResponse)
                        .toList(),
                nextCursor,
                hasNext);
    }

    /** 어드민 매거진 등록 — 배너·썸네일 이미지는 업로드 완료된 S3 키로 받아 키만 저장한다. */
    @Transactional
    public MagazineInfo create(String title, String bannerKey, String thumbnailKey, String instagramRedirectUrl) {
        Magazine magazine = magazineRepository.save(
                Magazine.create(title, bannerKey, thumbnailKey, instagramRedirectUrl));
        // 생성된 id는 응답 body에만 있어 로그로 남겨야 추적 가능하다 (adminId는 MDC)
        log.info("어드민 매거진 등록. magazineId={}", magazine.getId());
        return toInfo(magazine);
    }

    /** 어드민 매거진 수정 — 부분 수정(PATCH), null 필드는 변경하지 않는다. */
    @Transactional
    public MagazineInfo update(Long magazineId, String title, String bannerKey, String thumbnailKey,
                               String instagramRedirectUrl) {
        Magazine magazine = findMagazine(magazineId);
        magazine.update(title, bannerKey, thumbnailKey, instagramRedirectUrl);
        return toInfo(magazine);
    }

    /** 어드민 매거진 삭제. */
    @Transactional
    public void delete(Long magazineId) {
        Magazine magazine = findMagazine(magazineId);
        magazineRepository.delete(magazine);
        // hard delete라 사후 추적 수단이 로그뿐이다 (adminId는 MDC)
        log.info("어드민 매거진 삭제. magazineId={}", magazineId);
    }

    private Magazine findMagazine(Long magazineId) {
        return magazineRepository.findById(magazineId)
                .orElseThrow(() -> new BusinessException(MagazineErrorCode.NOT_FOUND));
    }

    private List<Magazine> fetchPage(Long cursor, Pageable pageable) {
        return (cursor == null)
                ? magazineRepository.findAllByOrderByIdDesc(pageable)
                : magazineRepository.findByIdLessThanOrderByIdDesc(cursor, pageable);
    }

    // 페이지 사이즈 검증
    private int normalizeSize(Integer size) {
        if (size == null || size < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    // 매거진 큐레이선 응답
    private MagazineBannerResponse toBannerResponse(Magazine magazine) {
        return new MagazineBannerResponse(
                magazine.getId(),
                magazine.getTitle(),
                fileStorage.resolveFileUrl(magazine.getBannerKey()),
                magazine.getInstagramRedirectUrl());
    }

    // 매거진 리스트 응답
    private MagazineSummaryResponse toSummaryResponse(Magazine magazine) {
        return new MagazineSummaryResponse(
                magazine.getId(),
                magazine.getTitle(),
                fileStorage.resolveFileUrl(magazine.getBannerKey()),
                fileStorage.resolveFileUrl(magazine.getThumbnailKey()),
                magazine.getInstagramRedirectUrl(),
                magazine.getCreatedAt());
    }

    private MagazineInfo toInfo(Magazine magazine) {
        return new MagazineInfo(
                magazine.getId(),
                magazine.getTitle(),
                fileStorage.resolveFileUrl(magazine.getBannerKey()),
                fileStorage.resolveFileUrl(magazine.getThumbnailKey()),
                magazine.getInstagramRedirectUrl(),
                magazine.getCreatedAt());
    }
}
