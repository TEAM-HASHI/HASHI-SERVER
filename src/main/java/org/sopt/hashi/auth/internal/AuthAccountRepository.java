package org.sopt.hashi.auth.internal;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface AuthAccountRepository extends JpaRepository<AuthAccount, Long> {

    Optional<AuthAccount> findByProviderAndProviderUserId(AuthProvider provider, String providerUserId);
}
