package org.sopt.hashi.restaurant.migration;

import java.time.Clock;
import java.util.Map;
import org.sopt.hashi.restaurant.domain.Restaurant;
import org.sopt.hashi.restaurant.domain.RestaurantRepository;
import org.sopt.hashi.restaurant.service.RestaurantLocationService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Boot PropertiesLauncher entrypoint; deliberately excludes Flyway, web, workers and provider adapters. */
public final class LocationMaintenanceCli {
    private LocationMaintenanceCli() {
    }

    public static void main(String[] args) {
        try (var context = open(args)) {
            Object result = context.getBean(LocationMaintenanceRunner.class)
                    .execute(context.getBean(LocationMaintenanceProperties.class));
            System.out.println(result);
        } catch (RuntimeException exception) {
            System.err.println("Location maintenance failed. Check approved configuration and database access.");
            System.exit(1);
        }
    }

    static ConfigurableApplicationContext open(String... args) {
        SpringApplication app = new SpringApplication(CliConfiguration.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setAdditionalProfiles("location-maintenance-cli");
        app.setLogStartupInfo(false);
        app.setDefaultProperties(Map.of("spring.config.name", "location-maintenance",
                "spring.main.banner-mode", "off", "logging.level.root", "OFF"));
        app.addInitializers(context -> context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("maintenanceSafety", Map.of(
                        "spring.jpa.hibernate.ddl-auto", "validate", "spring.jpa.show-sql", "false",
                        "spring.sql.init.mode", "never", "spring.flyway.enabled", "false"))));
        return app.run(args);
    }

    // This profile keeps the CLI-only bootstrap out of normal application component scanning.
    @Configuration(proxyBeanMethods = false)
    @Profile("location-maintenance-cli")
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class, TransactionAutoConfiguration.class})
    @EntityScan(basePackageClasses = Restaurant.class)
    @EnableJpaRepositories(basePackageClasses = RestaurantRepository.class)
    @Import({LocationMaintenanceConfiguration.class, LocationMaintenanceReader.class,
            LocationMaintenanceStore.class, LocationMaintenanceTransactions.class,
            LocationMaintenanceInspection.class, LocationMaintenanceRunner.class,
            LocationRetentionService.class, LocationRetentionTransactions.class, RestaurantLocationService.class})
    static class CliConfiguration {
        @Bean("japanClock")
        Clock clock() {
            return Clock.systemUTC();
        }
    }
}
