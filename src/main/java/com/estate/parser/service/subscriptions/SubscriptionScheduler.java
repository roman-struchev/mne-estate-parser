package com.estate.parser.service.subscriptions;

import com.estate.parser.entity.AdEntity;
import com.estate.parser.repository.AdRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.similarity.JaroWinklerDistance;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;


@Service
@Slf4j
@RequiredArgsConstructor
public class SubscriptionScheduler {
    private final AdRepository adRepository;
    private final SubscriptionsConfiguration subscriptionsConfiguration;
    private final SubscriptionSender subscriptionSender;
    private final JaroWinklerDistance distance = new JaroWinklerDistance();

    /**
     * Send every 6 AM (GMT) updates for last 24h
     */
    @Scheduled(cron = "0 0 5 * * *")
    public void sendSubscriptions() {
        log.info("Start scheduler to send subscriptions");

        var dateFrom = LocalDate.now().minusDays(1);
        var updatedAds = adRepository.findAllByLastModifiedGreaterThanEqual(dateFrom.atStartOfDay(),
                Sort.by("type", "location", "size", "price"));

        subscriptionsConfiguration.getSubscriptions().forEach(s -> {
            var adsToSend = updatedAds.stream()
                    .filter(a -> CollectionUtils.isEmpty(s.getDistricts()) || a.getCity() == null || s.getDistricts().contains(a.getCity()))
                    .filter(a -> CollectionUtils.isEmpty(s.getLocations()) || a.getLocation() == null
                            || s.getLocations().stream().anyMatch(loc -> distance.apply(loc, a.getLocation()) < 0.1d))
                    .filter(a -> CollectionUtils.isEmpty(s.getTypes()) || a.getType() == null || s.getTypes().contains(a.getType().getDesc()))
                    .filter(a -> s.getPriceLessThen() == null || compareUnits(s.getPriceLessThen(), a.getPrice()) >= 0)
                    .filter(a -> s.getPriceLessThen() == null || compareUnits(s.getPriceMoreThen(), a.getPrice()) <= 0)
                    .filter(a -> s.getBedroomsLessThen() == null || compareUnits(s.getBedroomsLessThen(), a.getBedrooms()) >= 0)
                    .filter(a -> s.getBedroomsMoreThen() == null || compareUnits(s.getBedroomsMoreThen(), a.getBedrooms()) <= 0)
                    .filter(a -> s.getSizeLessThen() == null || compareUnits(s.getSizeLessThen(), a.getSize()) >= 0)
                    .filter(a -> s.getSizeMoreThen() == null || compareUnits(s.getSizeMoreThen(), a.getSize()) <= 0)
                    .toList();
            if (CollectionUtils.isEmpty(adsToSend)) {
                return;
            }

            var adsToSendByGroups = adsToSend.stream().collect(Collectors.groupingBy(AdEntity::getType, Collectors.toList()));
            var messageBody = adsToSendByGroups.entrySet().stream()
                    .filter(e -> !e.getValue().isEmpty())
                    .map(e -> {
                        var index = new AtomicInteger(1);
                        var groupTitle = e.getKey().getDesc();
                        var groupBody = e.getValue().stream()
                                .map(a -> {
                                    var location = StringUtils.defaultIfBlank(a.getLocation(), "?");
                                    var size = StringUtils.defaultIfBlank(a.getSize(), "?");
                                    var price = StringUtils.defaultIfBlank(a.getPrice(), "?");
                                    return String.format("%s. %s, %s, %s, %se, [%s](%s)", index.getAndIncrement(),
                                            a.getCity(), location, size, price, a.getSourceCode(), a.getSourceLink());
                                })
                                .collect(Collectors.joining("\n"));
                        return "*%s*\n%s".formatted(groupTitle, groupBody);
                    }).collect(Collectors.joining("\n\n"));
            var header = String.format("New ads since %s", dateFrom.format(DateTimeFormatter.ISO_DATE));
            subscriptionSender.sendToTelegram(s.getTelegramChatIds(), header, messageBody);
            subscriptionSender.sendToEmail(s.getEmails(), header, messageBody);
            log.info("Sent notification {}", messageBody);
        });
    }

    /**
     * Not int value in DB, try to parse as integer
     *
     * @param filter value from filter
     * @param value  value from ad
     * @return -1, 0, 1
     */
    private int compareUnits(Integer filter, String value) {
        try {
            if (filter == null || value == null) {
                return 0;
            }
            value = value.replaceAll("[.m]+", "").trim();
            return filter.compareTo(Integer.parseInt(value));
        } catch (Exception ex) {
            log.error("Can't compare {} with {}", filter, value, ex);
            return 0;
        }
    }
}
