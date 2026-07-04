package org.sopt.hashi.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA Auditing 활성화. {@code BaseTimeEntity}의 {@code @CreatedDate}/{@code @LastModifiedDate}를 자동 채운다.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
