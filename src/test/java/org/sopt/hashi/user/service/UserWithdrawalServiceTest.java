package org.sopt.hashi.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.hashi.auth.AuthAccountPort;
import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.media.MediaAssetPurpose;
import org.sopt.hashi.media.MediaAssetUse;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.user.UserWithdrawnEvent;
import org.sopt.hashi.user.WithdrawalBlocker;
import org.sopt.hashi.user.code.UserErrorCode;
import org.sopt.hashi.user.collection.domain.RestaurantCollectionRepository;
import org.sopt.hashi.user.domain.User;
import org.sopt.hashi.user.domain.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class UserWithdrawalServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RestaurantCollectionRepository restaurantCollectionRepository;

    @Mock
    private WithdrawalBlocker withdrawalBlocker;

    @Mock
    private MediaPort mediaPort;

    @Mock
    private AuthAccountPort authAccountPort;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private CurrentUserProvider currentUserProvider;

    private UserWithdrawalService service;

    @BeforeEach
    void setUp() {
        service = new UserWithdrawalService(userRepository, restaurantCollectionRepository,
                List.of(withdrawalBlocker), mediaPort, authAccountPort, eventPublisher, currentUserProvider);
        given(currentUserProvider.currentUserId()).willReturn(7L);
    }

    @Test
    void 탈퇴는_조건_검사_뒤_익명화_컬렉션_삭제_이미지_연결_해제_계정_정리_이벤트_발행을_순서대로_수행한다() {
        UUID assetId = UUID.randomUUID();
        User user = user(assetId);
        given(userRepository.findByIdForUpdate(7L)).willReturn(Optional.of(user));

        service.withdraw();

        assertThat(user.isDeleted()).isTrue();
        assertThat(user.getNickname()).isEqualTo("탈퇴회원#7");
        InOrder order = inOrder(withdrawalBlocker, restaurantCollectionRepository, mediaPort,
                authAccountPort, eventPublisher);
        order.verify(withdrawalBlocker).validateWithdrawable(7L);
        order.verify(restaurantCollectionRepository).deleteSavedRestaurantsByUserId(7L);
        order.verify(restaurantCollectionRepository).deleteByUserId(7L);
        order.verify(mediaPort).reconcileBindings(
                List.of(), List.of(new MediaAssetUse(assetId, MediaAssetPurpose.PROFILE)));
        order.verify(authAccountPort).unlinkWithdrawnAccount(7L);
        order.verify(eventPublisher).publishEvent(new UserWithdrawnEvent(7L));
    }

    @Test
    void 탈퇴_조건에_걸리면_구현_모듈의_예외가_그대로_나가고_아무것도_바꾸지_않는다() {
        User user = user(null);
        given(userRepository.findByIdForUpdate(7L)).willReturn(Optional.of(user));
        // 구현 모듈의 코드 대신 임의 코드 — 어떤 BusinessException이든 그대로 전파되는지만 본다
        doThrow(new BusinessException(CommonErrorCode.FORBIDDEN))
                .when(withdrawalBlocker).validateWithdrawable(7L);

        assertThatThrownBy(service::withdraw)
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.FORBIDDEN);

        assertThat(user.isDeleted()).isFalse();
        verifyNoInteractions(restaurantCollectionRepository, mediaPort, authAccountPort, eventPublisher);
    }

    @Test
    void 이미_탈퇴했거나_없는_회원이면_NOT_FOUND로_거부한다() {
        given(userRepository.findByIdForUpdate(7L)).willReturn(Optional.empty());

        assertThatThrownBy(service::withdraw)
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.NOT_FOUND);

        verifyNoInteractions(withdrawalBlocker, restaurantCollectionRepository, mediaPort,
                authAccountPort, eventPublisher);
    }

    @Test
    void 프로필_asset이_없으면_media에_연결_해제를_요청하지_않는다() {
        given(userRepository.findByIdForUpdate(7L)).willReturn(Optional.of(user(null)));

        service.withdraw();

        verify(mediaPort, never()).reconcileBindings(any(), any());
        verify(authAccountPort).unlinkWithdrawnAccount(7L);
    }

    private User user(UUID assetId) {
        User user = User.onboard(
                "하시", "HASHI", LocalDate.of(1998, 1, 1),
                "01012345678", "hashi@example.com", null, assetId);
        ReflectionTestUtils.setField(user, "id", 7L);
        return user;
    }
}
