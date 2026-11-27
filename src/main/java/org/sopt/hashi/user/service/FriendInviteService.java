package org.sopt.hashi.user.service;

import java.net.URI;
import java.security.SecureRandom;
import java.util.Locale;
import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.user.code.UserErrorCode;
import org.sopt.hashi.user.domain.User;
import org.sopt.hashi.user.domain.UserRepository;
import org.sopt.hashi.user.dto.FriendInviteResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class FriendInviteService {
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final UserRepository users;
    private final CurrentUserProvider currentUser;
    private final URI sharePage;

    public FriendInviteService(UserRepository users, CurrentUserProvider currentUser,
                               @Value("${hashi.invite.share-page:https://hashi.kr/}") URI sharePage) {
        this.users = users;
        this.currentUser = currentUser;
        this.sharePage = sharePage;
    }

    @Transactional
    public FriendInviteResponse getMine() {
        User user = users.findByIdForUpdate(currentUser.currentUserId())
                .orElseThrow(() -> new BusinessException(UserErrorCode.NOT_FOUND));
        String code = users.findInviteCode(user.getId()).orElseGet(() -> allocate(user.getId()));
        String link = UriComponentsBuilder.fromUri(sharePage).replaceQueryParam("inviteCode", code)
                .build().toUriString();
        return new FriendInviteResponse(code, link);
    }

    private String allocate(Long userId) {
        for (int attempt = 0; attempt < 5; attempt++) {
            StringBuilder code = new StringBuilder(10);
            for (int i = 0; i < 10; i++) {
                code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            }
            String candidate = code.toString();
            if (users.assignInviteCode(userId, candidate) == 1) {
                return candidate;
            }
        }
        throw new BusinessException(UserErrorCode.INVITE_CODE_UNAVAILABLE);
    }

    @Transactional(readOnly = true)
    public boolean isValid(String code) {
        return users.existsByInviteCodeAndDeletedFalse(normalize(code));
    }

    static String normalize(String code) {
        return code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
    }
}
