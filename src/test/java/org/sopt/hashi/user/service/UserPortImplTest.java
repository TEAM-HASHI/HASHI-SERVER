package org.sopt.hashi.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.hashi.shared.storage.FileStorage;
import org.sopt.hashi.user.UserProfileInfo;
import org.sopt.hashi.user.domain.User;
import org.sopt.hashi.user.domain.UserRepository;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class UserPortImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private FileStorage fileStorage;

    private UserPortImpl userPort;

    @BeforeEach
    void setUp() {
        userPort = new UserPortImpl(userRepository, fileStorage);
    }

    @Test
    void 회원_프로필_목록은_요청_순서를_유지하고_중복과_존재하지_않는_회원을_제외한다() {
        User first = createUser(1L, "하루", null);
        User second = createUser(2L, "소라", "users/2/profile.jpg");
        given(userRepository.findAllById(List.of(2L, 3L, 1L)))
                .willReturn(List.of(first, second));
        given(fileStorage.resolveFileUrl("users/2/profile.jpg"))
                .willReturn("https://cdn.example.com/users/2/profile.jpg");

        List<UserProfileInfo> result = userPort.findProfiles(Arrays.asList(2L, 3L, 1L, 2L, null));

        assertThat(result).containsExactly(
                new UserProfileInfo(2L, "소라", "https://cdn.example.com/users/2/profile.jpg"),
                new UserProfileInfo(1L, "하루", null)
        );
        verify(userRepository).findAllById(List.of(2L, 3L, 1L));
    }

    @Test
    void 회원_id_목록이_비어_있으면_조회하지_않는다() {
        assertThat(userPort.findProfiles(List.of())).isEmpty();

        verifyNoInteractions(userRepository, fileStorage);
    }

    private User createUser(Long id, String nickname, String profileImageKey) {
        User user = User.onboard(
                nickname,
                "HASHI USER",
                LocalDate.of(1998, 5, 12),
                "010-0000-0000",
                "%s@hashi.com".formatted(id),
                profileImageKey
        );
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
