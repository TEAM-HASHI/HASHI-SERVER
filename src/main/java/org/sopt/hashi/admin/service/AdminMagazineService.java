package org.sopt.hashi.admin.service;

import java.util.UUID;
import org.sopt.hashi.admin.dto.AdminMagazineResponse;
import org.sopt.hashi.admin.dto.CreateMagazineRequest;
import org.sopt.hashi.admin.dto.UpdateMagazineRequest;
import org.sopt.hashi.magazine.AdminMagazineCommand;
import org.sopt.hashi.magazine.AdminMagazineCommand.ImageCommand;
import org.sopt.hashi.magazine.MagazinePort;
import org.springframework.stereotype.Service;

/**
 * 어드민 매거진 관리 — 진입점 모듈이라 도메인 로직 없이 {@link MagazinePort}로 위임하고
 * 응답 DTO 변환만 한다(architecture.md §9). 저장·검증·URL 변환은 magazine 소관.
 */
@Service
public class AdminMagazineService {

    private final MagazinePort magazinePort;

    public AdminMagazineService(MagazinePort magazinePort) {
        this.magazinePort = magazinePort;
    }

    public AdminMagazineResponse create(CreateMagazineRequest request) {
        return AdminMagazineResponse.from(magazinePort.createByAdmin(
                new AdminMagazineCommand(
                        request.title(),
                        imageCommand(request.bannerKey(), request.bannerImageAssetId()),
                        imageCommand(request.thumbnailKey(), request.thumbnailImageAssetId()),
                        request.instagramRedirectUrl())));
    }

    public AdminMagazineResponse update(Long magazineId, UpdateMagazineRequest request) {
        return AdminMagazineResponse.from(magazinePort.updateByAdmin(
                magazineId,
                new AdminMagazineCommand(
                        request.title(),
                        imageCommand(request.bannerKey(), request.bannerImageAssetId()),
                        imageCommand(request.thumbnailKey(), request.thumbnailImageAssetId()),
                        request.instagramRedirectUrl())));
    }

    public void delete(Long magazineId) {
        magazinePort.deleteByAdmin(magazineId);
    }

    private ImageCommand imageCommand(String imageKey, UUID imageAssetId) {
        if (imageKey == null && imageAssetId == null) {
            return null;
        }
        return new ImageCommand(imageKey, imageAssetId);
    }
}
