package com.localbuddy;

import com.localbuddy.attendance.CheckInProperties;
import com.localbuddy.notification.email.AzureCommunicationProperties;
import com.localbuddy.notification.email.EmailProperties;
import com.localbuddy.payment.StripeProperties;
import com.localbuddy.ratelimit.RateLimitProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@EnableConfigurationProperties({
        StripeProperties.class,
        EmailProperties.class,
        AzureCommunicationProperties.class,
        RateLimitProperties.class,
        CheckInProperties.class
})
@SpringBootApplication
public class LocalbuddyBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(LocalbuddyBackendApplication.class, args);
    }
}