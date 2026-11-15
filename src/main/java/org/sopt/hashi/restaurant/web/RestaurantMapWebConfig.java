package org.sopt.hashi.restaurant.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 지도 목록의 DB 연결을 Redis 대기 전에 반환하고 나머지 경로의 기존 OSIV 동작은 유지한다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "spring.jpa.open-in-view", havingValue = "true", matchIfMissing = true)
public class RestaurantMapWebConfig {

    /** 이 표준 interceptor 빈이 있으면 Boot의 기본 OSIV 자동 등록은 물러난다. */
    @Bean
    OpenEntityManagerInViewInterceptor openEntityManagerInViewInterceptor() {
        return new OpenEntityManagerInViewInterceptor();
    }

    @Bean
    WebMvcConfigurer restaurantMapOpenEntityManagerInViewConfigurer(OpenEntityManagerInViewInterceptor interceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addWebRequestInterceptor(interceptor).excludePathPatterns("/api/v1/restaurants/map");
            }
        };
    }
}
