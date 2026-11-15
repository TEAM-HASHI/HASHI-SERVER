package org.sopt.hashi.media.internal.cleanup;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MediaCleanupProperties.class)
public class MediaCleanupPropertiesConfig {
}
