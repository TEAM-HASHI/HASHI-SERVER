package org.sopt.hashi.restaurant.internal.map.places;

import org.sopt.hashi.restaurant.internal.map.GeocodingResult.FailureKind;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GooglePlacesProperties.class)
public class GooglePlacesConfig {

    @Bean
    public PlacesProvider placesProvider(GooglePlacesProperties properties) {
        if (!properties.enabled()) {
            return new PlacesProvider() {
                @Override
                public PlacesSearchResult search(String query) {
                    return new PlacesSearchResult.Failure(FailureKind.DISABLED, null);
                }

                @Override
                public PlaceDetailsResult details(String placeId) {
                    return new PlaceDetailsResult.Failure(FailureKind.DISABLED, null);
                }
            };
        }
        properties.validateEnabled();
        return new GooglePlacesProvider(properties);
    }
}
