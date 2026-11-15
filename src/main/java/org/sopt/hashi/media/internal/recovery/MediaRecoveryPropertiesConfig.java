package org.sopt.hashi.media.internal.recovery;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MediaRecoveryProperties.class)
public class MediaRecoveryPropertiesConfig {
}
