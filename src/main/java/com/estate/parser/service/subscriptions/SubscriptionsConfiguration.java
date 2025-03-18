package com.estate.parser.service.subscriptions;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "app")
@Data
public class SubscriptionsConfiguration {
    List<Subscription> subscriptions;

    @Data
    public static class Subscription {
        private List<String> telegramChatIds;
        private List<String> emails;
        private List<String> types;
        private List<String> districts;
        private List<String> locations;
        private Integer priceLessThen;
        private Integer priceMoreThen;
        private Integer bedroomsLessThen;
        private Integer bedroomsMoreThen;
        private Integer sizeLessThen;
        private Integer sizeMoreThen;
    }
}
