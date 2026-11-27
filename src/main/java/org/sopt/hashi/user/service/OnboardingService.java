package org.sopt.hashi.user.service;

import lombok.extern.slf4j.Slf4j;
import org.sopt.hashi.auth.AuthAccountPort;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.user.FriendInviteReward;
import org.sopt.hashi.user.code.UserErrorCode;
import org.sopt.hashi.user.domain.User;
import org.sopt.hashi.user.domain.UserRepository;
import org.sopt.hashi.user.dto.CompleteOnboardingRequest;
import org.sopt.hashi.user.dto.OnboardingResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class OnboardingService {

    private final UserRepository userRepository;
    private final AuthAccountPort authAccountPort;
    private final MediaPort mediaPort;
    private final ProfileAvailabilityChecker availabilityChecker;
    private final FriendInviteReward inviteReward;
    private final SignupPhoneService phoneProof;

    public OnboardingService(UserRepository userRepository,
                             AuthAccountPort authAccountPort,
                             MediaPort mediaPort,
                             ProfileAvailabilityChecker availabilityChecker,
                             FriendInviteReward inviteReward, SignupPhoneService phoneProof) {
        this.userRepository = userRepository;
        this.authAccountPort = authAccountPort;
        this.mediaPort = mediaPort;
        this.availabilityChecker = availabilityChecker;
        this.inviteReward = inviteReward;
        this.phoneProof = phoneProof;
    }

    /**
     * 온보딩 프로필을 저장해 가입을 완료한다. 회원 생성과 소셜 계정 연결을 한 트랜잭션에서 처리해
     * 둘 중 하나만 남는 상태를 막는다(연결 대상 제공자·식별자는 auth가 온보딩 컨텍스트에서 읽는다).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OnboardingResponse completeOnboarding(CompleteOnboardingRequest request) {
        // 닉네임·이메일·연락처는 유니크다 — 가입은 제외할 본인이 없으므로 전체 회원과 비교한다
        availabilityChecker.requireAvailable(request.nickname(), request.email(), request.phone(), null);
        String code = FriendInviteService.normalize(request.inviteCode());
        Long inviterId = code.isEmpty() ? null : userRepository.findInviterForUpdate(code)
                .orElseThrow(() -> new BusinessException(UserErrorCode.INVALID_INVITE_CODE)).getId();
        byte[] verifiedPhone = phoneProof.consumeForSignup(request.phone(), inviterId != null);
        User user = saveUser(request, inviterId);
        authAccountPort.linkOnboardingAccount(user.getId());
        if (request.profileImageAssetId() != null) {
            mediaPort.claimOnboardingProfile(request.profileImageAssetId(), user.getId());
            user.assignOnboardingProfileImage(request.profileImageAssetId());
        }
        if (inviterId != null && phoneProof.claimReward(verifiedPhone)) {
            inviteReward.grant(inviterId, user.getId());
        }
        // 회원 생성 시점 기록 — 프로필 값은 개인정보라 ID만 남긴다
        log.info("온보딩 가입 완료. userId={}", user.getId());
        return new OnboardingResponse(user.getId());
    }

    private User saveUser(CompleteOnboardingRequest request, Long inviterId) {
        try {
            // asset ID의 소유권 검증보다 users의 unique 제약이 먼저 실행되지 않게 한다.
            // USER ID를 만든 뒤 같은 transaction에서 claim하고 프로필 슬롯에 연결한다.
            User user = User.onboard(
                    request.nickname(), request.nameEng(), request.birthDate(),
                    request.phone(), request.email(), request.profileImageKey());
            if (inviterId != null) {
                user.invitedBy(inviterId);
            }
            return userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            // 사전 검사와 저장 사이의 동시 가입 경합 — 유니크 제약(nickname·email·phone)이 최종 방어한다.
            // 롤백된 트랜잭션에서 어느 필드인지 재조회하는 건 불안정하므로 일반 충돌로 변환한다.
            throw new BusinessException(UserErrorCode.DUPLICATE_USER_INFO, e);
        }
    }
}
