package org.sopt.hashi.restaurant.internal.map.google;

import org.sopt.hashi.restaurant.internal.map.GeocodingProvider;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.Failure;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.FailureKind;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GoogleGeocodingProperties.class)
public class GoogleGeocodingConfig {

    @Bean
    public GeocodingProvider geocodingProvider(GoogleGeocodingProperties properties) {
        if (!properties.enabled()) {
            return address -> new Failure(FailureKind.DISABLED, null);
        }
        properties.validateEnabled();
        return new GoogleGeocodingProvider(properties);
    }
}
