package org.sopt.hashi.media.internal.reconciliation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MediaReconciliationProperties.class)
public class MediaReconciliationPropertiesConfig {
}
