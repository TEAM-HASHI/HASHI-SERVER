package org.sopt.hashi.media.internal.queue;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MediaQueueProperties.class)
public class MediaQueuePropertiesConfig {
}
