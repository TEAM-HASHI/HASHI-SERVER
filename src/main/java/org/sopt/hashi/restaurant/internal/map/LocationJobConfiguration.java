package org.sopt.hashi.restaurant.internal.map;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LocationJobProperties.class)
public class LocationJobConfiguration {
}
