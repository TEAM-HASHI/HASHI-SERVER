package org.sopt.hashi.magazine.service;

import org.sopt.hashi.magazine.AdminMagazineCommand;
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
    public MagazineInfo createByAdmin(AdminMagazineCommand command) {
        return magazineService.create(command);
    }

    @Override
    public MagazineInfo updateByAdmin(Long magazineId, AdminMagazineCommand command) {
        return magazineService.update(magazineId, command);
    }

    @Override
    public void deleteByAdmin(Long magazineId) {
        magazineService.delete(magazineId);
    }
}
