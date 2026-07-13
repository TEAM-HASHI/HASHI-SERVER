package org.sopt.hashi.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfig {

    private static final ZoneId JAPAN_ZONE_ID = ZoneId.of("Asia/Tokyo");

    @Bean("japanClock")
    public Clock japanClock() {
        return Clock.system(JAPAN_ZONE_ID);
    }
}
