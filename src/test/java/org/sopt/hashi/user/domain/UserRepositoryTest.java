package org.sopt.hashi.user.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

/**
 * 전역 soft delete 필터를 걷어냈으므로, 활성 회원만 다루는 조회마다 deleted 조건이 빠지지 않았는지 고정한다.
 * 탈퇴 회원을 포함해야 하는 조회는 리뷰 작성자 표시용 findAllById뿐이다.
 */
@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:user-repository-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE"
})
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void 활성_회원_조회는_탈퇴_회원을_제외하고_프로필_일괄_조회만_포함한다() {
        User active = save("활성회원", "01011110001", "active@hashi.test");
        User withdrawn = save("탈퇴할회원", "01011110002", "withdrawn@hashi.test");
        withdrawn.withdraw();
        entityManager.flush();
        entityManager.clear();

        assertThat(userRepository.findByIdAndDeletedFalse(active.getId())).isPresent();
        assertThat(userRepository.findByIdAndDeletedFalse(withdrawn.getId())).isEmpty();
        assertThat(userRepository.findByIdForUpdate(active.getId())).isPresent();
        assertThat(userRepository.findByIdForUpdate(withdrawn.getId())).isEmpty();
        assertThat(userRepository.existsByIdAndDeletedFalse(active.getId())).isTrue();
        assertThat(userRepository.existsByIdAndDeletedFalse(withdrawn.getId())).isFalse();
        assertThat(userRepository.findByDeletedFalse(PageRequest.of(0, 10)).getContent())
                .extracting(User::getId)
                .containsExactly(active.getId());
        // 탈퇴 자리값 "탈퇴회원#n"도 "회원"을 포함하지만 어드민 검색에서 제외된다
        assertThat(userRepository.findByNicknameContainingAndDeletedFalse("회원", PageRequest.of(0, 10)).getContent())
                .extracting(User::getId)
                .containsExactly(active.getId());
        assertThat(userRepository.findAllById(List.of(active.getId(), withdrawn.getId())))
                .extracting(User::getId)
                .containsExactlyInAnyOrder(active.getId(), withdrawn.getId());
    }

    @Test
    void 탈퇴_회원의_자리값은_유니크_제약과_충돌하지_않고_원래_정보로_다시_가입할_수_있다() {
        User first = save("첫회원", "01011110001", "first@hashi.test");
        first.withdraw();
        entityManager.flush();

        User rejoined = save("첫회원", "01011110001", "first@hashi.test");
        entityManager.flush();
        entityManager.clear();

        assertThat(rejoined.getId()).isNotEqualTo(first.getId());
        assertThat(userRepository.existsByNickname("탈퇴회원#" + first.getId())).isTrue();
        assertThat(userRepository.findByIdAndDeletedFalse(rejoined.getId()))
                .hasValueSatisfying(user -> assertThat(user.getNickname()).isEqualTo("첫회원"));
    }

    private User save(String nickname, String phone, String email) {
        User user = User.onboard(nickname, "HASHI", LocalDate.of(1998, 1, 1), phone, email, null);
        entityManager.persistAndFlush(user);
        return user;
    }
}
