package org.sopt.hashi.restaurant.internal.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class MapQueryPropertiesTest {

    @ParameterizedTest
    @ValueSource(strings = {"", "bad-number", "NaN", "91", "0"})
    void 설정_누락이나_오류가_서버_시작을_막지_않고_지도_요청에서만_503이다(String value) {
        new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class)
                .withPropertyValues("hashi.restaurant.map.initial-bounds.south=" + value)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThatThrownBy(() -> context.getBean(MapQueryProperties.class).requireConfiguration())
                            .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(RestaurantErrorCode.MAP_CONFIGURATION_UNAVAILABLE));
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MapQueryProperties.class)
    static class PropertiesConfiguration {
    }
}
