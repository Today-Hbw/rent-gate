package io.rentgate;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RentGateApplication {
    public static void main(String[] args) {
        SpringApplication.run(RentGateApplication.class, args);
    }

    @Bean
    Clock clock() { return Clock.systemUTC(); }
}
