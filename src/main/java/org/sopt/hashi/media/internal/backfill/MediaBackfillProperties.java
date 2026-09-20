package org.sopt.hashi.media.internal.backfill;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 신규 media 활성화와 별도로, 승인된 legacy 전환 실행 환경에서만 opt-in한다. */
@ConfigurationProperties(prefix = "hashi.media.backfill")
public record MediaBackfillProperties(boolean enabled) {
}
