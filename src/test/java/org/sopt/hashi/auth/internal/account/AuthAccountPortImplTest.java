package org.sopt.hashi.auth.internal.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.sopt.hashi.auth.internal.jwt.AuthRoles;
import org.sopt.hashi.auth.internal.token.RefreshTokenStore;
import org.sopt.hashi.auth.internal.token.TokenBlacklist;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 탈퇴 계정 정리는 계정 연결 삭제·리프레시 폐기·블랙리스트 등록을 모두 수행해야 하고,
 * 탈퇴 트랜잭션이 롤백되면 블랙리스트만 되돌려 회원이 14일간 묶이는 일을 막아야 한다.
 */
class AuthAccountPortImplTest {

    private final AuthAccountService authAccountService = mock(AuthAccountService.class);
    private final RefreshTokenStore refreshTokenStore = mock(RefreshTokenStore.class);
    private final TokenBlacklist tokenBlacklist = mock(TokenBlacklist.class);
    private final AuthAccountPortImpl port = new AuthAccountPortImpl(
            authAccountService, refreshTokenStore, tokenBlacklist);

    @BeforeEach
    void setUp() {
        // 프록시 없는 단위 테스트라 탈퇴 트랜잭션의 동기화 컨텍스트를 직접 연다
        TransactionSynchronizationManager.initSynchronization();
        given(tokenBlacklist.blockUser(7L)).willReturn("withdrawn:mine");
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    @DisplayName("계정 연결 삭제 → 리프레시 폐기 → 블랙리스트 등록 순으로 정리한다")
    void 탈퇴_계정_정리() {
        port.unlinkWithdrawnAccount(7L);

        InOrder order = inOrder(authAccountService, refreshTokenStore, tokenBlacklist);
        order.verify(authAccountService).unlink(7L);
        order.verify(refreshTokenStore).revoke(AuthRoles.USER, 7L);
        order.verify(tokenBlacklist).blockUser(7L);
    }

    @Test
    @DisplayName("탈퇴 트랜잭션이 롤백되면 이 요청의 표식만 되돌리고, 커밋되면 그대로 둔다")
    void 롤백_시_블랙리스트_복구() {
        port.unlinkWithdrawnAccount(7L);
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).hasSize(1);

        synchronizations.getFirst().afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        verify(tokenBlacklist, never()).unblockUser(7L, "withdrawn:mine");

        synchronizations.getFirst().afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(tokenBlacklist).unblockUser(7L, "withdrawn:mine");
    }
}
