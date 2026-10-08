package org.sopt.hashi.user.service;

import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.sopt.hashi.auth.AuthAccountPort;
import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.media.MediaAssetPurpose;
import org.sopt.hashi.media.MediaAssetUse;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.user.UserWithdrawnEvent;
import org.sopt.hashi.user.WithdrawalBlocker;
import org.sopt.hashi.user.code.UserErrorCode;
import org.sopt.hashi.user.collection.domain.RestaurantCollectionRepository;
import org.sopt.hashi.user.domain.User;
import org.sopt.hashi.user.domain.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 탈퇴(SPRINT-001 §3). 대상은 항상 {@link CurrentUserProvider}의 현재 사용자다(auth.md §2).
 * 회원 행을 잠근 뒤 탈퇴 조건({@link WithdrawalBlocker} — 조건을 소유한 모듈이 구현)을 검사하고, soft delete·익명화,
 * 식당 컬렉션 삭제, 프로필 사진 연결 해제(MediaPort), 인증 계정·토큰 정리(AuthAccountPort)를 한 트랜잭션에서 처리한다.
 * 다른 모듈의 후속 정리(포인트 소멸 등)는 {@link UserWithdrawnEvent}로 알린다 — Event Publication Registry에
 * 같은 트랜잭션으로 기록돼 커밋 뒤 각자 트랜잭션에서 처리되고, 실패해도 재제출된다(architecture.md §6·§8).
 */
@Slf4j
@Service
public class UserWithdrawalService {

    private final UserRepository userRepository;
    private final RestaurantCollectionRepository restaurantCollectionRepository;
    private final List<WithdrawalBlocker> withdrawalBlockers;
    private final MediaPort mediaPort;
    private final AuthAccountPort authAccountPort;
    private final ApplicationEventPublisher eventPublisher;
    private final CurrentUserProvider currentUserProvider;

    public UserWithdrawalService(UserRepository userRepository,
                                 RestaurantCollectionRepository restaurantCollectionRepository,
                                 List<WithdrawalBlocker> withdrawalBlockers,
                                 MediaPort mediaPort,
                                 AuthAccountPort authAccountPort,
                                 ApplicationEventPublisher eventPublisher,
                                 CurrentUserProvider currentUserProvider) {
        this.userRepository = userRepository;
        this.restaurantCollectionRepository = restaurantCollectionRepository;
        this.withdrawalBlockers = withdrawalBlockers;
        this.mediaPort = mediaPort;
        this.authAccountPort = authAccountPort;
        this.eventPublisher = eventPublisher;
        this.currentUserProvider = currentUserProvider;
    }

    /** 탈퇴 — 이미 탈퇴한 회원(잠금 조회에서 제외)은 NOT_FOUND, 조건에 걸리면 구현 모듈의 에러(예: RESERVATION-008)가 그대로 나간다. */
    @Transactional
    public void withdraw() {
        Long userId = currentUserProvider.currentUserId();
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.NOT_FOUND));
        withdrawalBlockers.forEach( blocker -> blocker.validateWithdrawable(userId));

        UUID profileImageAssetId = user.getProfileImageAssetId();
        // user의 nickname, email, phone을 탈퇴 회원 포맷으로 변경, 프로필 사진 비우기
        user.withdraw();
        deleteCollections(userId);
        retireProfileImage(profileImageAssetId);
        authAccountPort.unlinkWithdrawnAccount(userId);
        eventPublisher.publishEvent(new UserWithdrawnEvent(userId));
        // 탈퇴 시점 기록 — 인증 계정·토큰이 함께 정리되므로 이후 같은 userId의 요청은 모두 거부된다
        log.info("회원 탈퇴. userId={}", userId);
    }

    /** 식당 컬렉션은 user 애그리거트의 하위 도메인이라 함께 지운다 — 저장 식당 매핑을 먼저 지운다(같은 애그리거트 내부 FK). */
    private void deleteCollections(Long userId) {
        restaurantCollectionRepository.deleteSavedRestaurantsByUserId(userId);
        restaurantCollectionRepository.deleteByUserId(userId);
    }

    /** 신규 파이프라인 asset만 media에 연결 해제를 알린다 — 기존 key 사진은 User의 참조만 끊는다(물리 삭제 정책은 ADR 0001 후속). */
    private void retireProfileImage(UUID profileImageAssetId) {
        if (profileImageAssetId != null) {
            mediaPort.reconcileBindings(List.of(), List.of(
                    new MediaAssetUse(profileImageAssetId, MediaAssetPurpose.PROFILE)));
        }
    }
}
