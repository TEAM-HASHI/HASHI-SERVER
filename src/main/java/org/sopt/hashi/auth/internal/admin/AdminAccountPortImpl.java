package org.sopt.hashi.auth.internal.admin;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.sopt.hashi.auth.ActorType;
import org.sopt.hashi.auth.AdminAccountPort;
import org.sopt.hashi.auth.CurrentActorProvider;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class AdminAccountPortImpl implements AdminAccountPort {

    private final AdminRepository admins;
    private final CurrentActorProvider actors;

    AdminAccountPortImpl(AdminRepository admins, CurrentActorProvider actors) {
        this.admins = admins;
        this.actors = actors;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, String> findLoginIds(Set<Long> adminIds) {
        if (actors.currentActor().type() != ActorType.ADMIN) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        if (adminIds.isEmpty()) {
            return Map.of();
        }
        return admins.findLoginIds(adminIds).stream().collect(Collectors.toUnmodifiableMap(
                AdminRepository.AdminLoginIdProjection::getId,
                AdminRepository.AdminLoginIdProjection::getLoginId));
    }
}
