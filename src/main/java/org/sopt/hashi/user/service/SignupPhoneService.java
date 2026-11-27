package org.sopt.hashi.user.service;

import java.util.UUID;
import org.sopt.hashi.auth.AuthAccountPort;
import org.sopt.hashi.auth.CurrentActorProvider;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.user.code.UserErrorCode;
import org.sopt.hashi.user.domain.SignupPhoneStore;
import org.sopt.hashi.user.internal.phone.OctomoClient;
import org.springframework.stereotype.Service;

@Service
public class SignupPhoneService {
    private final SignupPhonePolicy policy;
    private final AuthAccountPort auth;
    private final CurrentActorProvider actors;
    private final SignupPhoneStore store;
    private final OctomoClient octomo;

    public SignupPhoneService(SignupPhonePolicy policy, AuthAccountPort auth, CurrentActorProvider actors,
                              SignupPhoneStore store, OctomoClient octomo) {
        this.policy = policy;
        this.auth = auth;
        this.actors = actors;
        this.store = store;
        this.octomo = octomo;
    }

    public record Challenge(UUID challengeId, String recipient, String text, int expiresInSeconds) {}

    public Challenge issue(String phone) {
        requireEnabled();
        UUID challenge = UUID.randomUUID();
        store.issue(policy.digest("actor-limit", actors.currentActor().subjectId().toString()),
                policy.digest("phone-limit", phone), session(), phoneDigest(phone), challenge.toString());
        return new Challenge(challenge, "1666-3538", message(challenge), 300);
    }

    public boolean confirm(String phone, UUID challenge) {
        requireEnabled();
        byte[] session = session();
        byte[] digest = phoneDigest(phone);
        if (!store.beginCheck(session, digest, challenge.toString())) {
            return true;
        }
        if (!octomo.exists(phone, message(challenge))) {
            return false;
        }
        store.markVerified(session, digest, challenge.toString());
        return true;
    }

    byte[] consumeForSignup(String phone, boolean hasInvite) {
        if (!policy.enabled) {
            if (hasInvite) {
                throw new BusinessException(UserErrorCode.INVITE_REWARD_UNAVAILABLE);
            }
            return null;
        }
        byte[] digest = phoneDigest(phone);
        store.consume(session(), digest);
        return digest;
    }

    boolean claimReward(byte[] verifiedPhone) {
        return verifiedPhone != null && store.claimReward(verifiedPhone);
    }

    private String message(UUID challenge) {
        return "HASHI " + challenge;
    }

    private byte[] phoneDigest(String phone) {
        return policy.digest("reward-phone-v1", phone);
    }

    private byte[] session() {
        return policy.digest("onboarding-session", auth.onboardingSessionId());
    }

    private void requireEnabled() {
        if (!policy.enabled) {
            throw new BusinessException(UserErrorCode.PHONE_PROOF_UNAVAILABLE);
        }
    }
}
