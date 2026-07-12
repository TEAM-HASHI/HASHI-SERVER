package org.sopt.hashi.user.dev;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.user.code.UserErrorCode;
import org.sopt.hashi.user.domain.User;
import org.sopt.hashi.user.domain.UserRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 개발용 더미 회원 생성 — local·dev 프로필에서만 빈이 등록된다(운영에는 존재 자체가 없음).
 * 생성된 회원은 auth_account가 없어 소셜 로그인은 불가하며,
 * /api/v1/auth/dev/tokens 에서 userId로 USER 토큰을 발급받아 사용한다.
 */
@Profile({"local", "dev"})
@Service
public class DevUserDataGenerator {

    private static final String TOKEN_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int TOKEN_LENGTH = 8;
    private static final LocalDate BIRTH_FROM = LocalDate.of(1970, 1, 1);
    private static final LocalDate BIRTH_TO = LocalDate.of(2005, 12, 31);

    private final UserRepository userRepository;

    public DevUserDataGenerator(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** 더미 회원 count명을 생성하고 userId 목록을 반환한다. */
    @Transactional
    public List<Long> createUsers(int count) {
        List<User> users = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            users.add(newDummyUser());
        }
        try {
            return userRepository.saveAll(users).stream().map(User::getId).toList();
        } catch (DataIntegrityViolationException e) {
            // 난수 식별자 공간(36^8·10^8)에서 충돌은 사실상 없지만, 유니크 제약(nickname·email·phone)이 최종 방어한다.
            throw new BusinessException(UserErrorCode.DUPLICATE_USER_INFO, e);
        }
    }

    private User newDummyUser() {
        String token = randomToken();
        return User.onboard(
                "더미-" + token,
                "Dummy",
                randomBirthDate(),
                randomPhone(),
                "dummy-" + token + "@hashi.dev",
                null);
    }

    private String randomToken() {
        StringBuilder token = new StringBuilder(TOKEN_LENGTH);
        for (int i = 0; i < TOKEN_LENGTH; i++) {
            token.append(TOKEN_CHARS.charAt(ThreadLocalRandom.current().nextInt(TOKEN_CHARS.length())));
        }
        return token.toString();
    }

    private String randomPhone() {
        return "010" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }

    private LocalDate randomBirthDate() {
        return LocalDate.ofEpochDay(
                ThreadLocalRandom.current().nextLong(BIRTH_FROM.toEpochDay(), BIRTH_TO.toEpochDay() + 1));
    }
}
