package org.sopt.hashi.media.internal.backfill;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MediaBackfillProperties.class)
public class MediaBackfillConfig {
}
