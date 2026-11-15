package org.sopt.hashi.restaurant.migration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LocationMaintenanceProperties.class)
public class LocationMaintenanceConfiguration {
}
