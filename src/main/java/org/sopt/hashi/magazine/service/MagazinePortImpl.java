package org.sopt.hashi.magazine.service;

import org.sopt.hashi.magazine.MagazineInfo;
import org.sopt.hashi.magazine.MagazinePort;
import org.springframework.stereotype.Component;

@Component
class MagazinePortImpl implements MagazinePort {

    private final MagazineService magazineService;

    MagazinePortImpl(MagazineService magazineService) {
        this.magazineService = magazineService;
    }

    @Override
    public MagazineInfo createByAdmin(String title, String bannerKey, String thumbnailKey,
                                      String instagramRedirectUrl) {
        return magazineService.create(title, bannerKey, thumbnailKey, instagramRedirectUrl);
    }

    @Override
    public MagazineInfo updateByAdmin(Long magazineId, String title, String bannerKey, String thumbnailKey,
                                      String instagramRedirectUrl) {
        return magazineService.update(magazineId, title, bannerKey, thumbnailKey, instagramRedirectUrl);
    }

    @Override
    public void deleteByAdmin(Long magazineId) {
        magazineService.delete(magazineId);
    }
}
