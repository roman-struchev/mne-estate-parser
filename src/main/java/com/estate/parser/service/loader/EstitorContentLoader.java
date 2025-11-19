package com.estate.parser.service.loader;

import com.estate.parser.entity.AdEntity;
import com.estate.parser.repository.AdRepository;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static com.estate.parser.entity.AdEntity.Type.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class EstitorContentLoader implements IContentLoader {

    private final AdRepository adRepository;

    @Value("${estitor.url:https://estitor.com}")
    private String baseUrl;

    @Override
    public List<String> loadAndSave() {
        var searches = List.of(
                "https://estitor.com/me-en/real-estates/purpose-rent",
                "https://estitor.com/me-en/real-estates/purpose-sale"
        );
        return searches.stream()
                .map(this::loadIdsBySearch)
                .flatMap(Collection::stream)
                .map(id -> loadAdAndSave(id, 1))
                .filter(Objects::nonNull)
                .map(AdEntity::getSourceId)
                .toList();
    }

    @Override
    public String getSourceName() {
        return "estitor";
    }

    @Override
    public boolean isCanBeDeleted(String sourceId) {
        var attributesMap = loadAdAttributes(sourceId, 1);
        return attributesMap == null || attributesMap.isEmpty();
    }

    /**
     * Load ids of ad from search
     *
     * @param urlWithAds
     * @return
     */
    @SneakyThrows
    private HashSet<String> loadIdsBySearch(String urlWithAds) {
        log.info("Start to load by filter: {}", urlWithAds);
        var links = new LinkedHashSet<String>();

        int curPage = 1;
        while (curPage >= 1) {
            try {
                var url = urlWithAds + (curPage > 1 ? "/page-" + curPage : "");
                var pageDoc = Jsoup.connect(url).get();
                var adElements = pageDoc.select(".estate-card > div > a");
                if (adElements.isEmpty() || !url.equals(pageDoc.location())) {
                    log.info("Last page {} of {}", curPage, url);
                    curPage = -1;
                    continue;
                } else {
                    log.info("Loaded page {} of {}", curPage, url);
                    curPage++;
                }

                adElements.forEach(el -> links.add(baseUrl + el.attr("href")));
            } catch (Exception e) {
                log.error("Can't load page with ad, goes to sleep 1s: {}", urlWithAds, e);
                Thread.sleep(1000);
            }
        }
        return links;
    }

    @SneakyThrows
    private Map<String, String> loadAdAttributes(String url, int repeats) {
        if (repeats < 0) {
            return null;
        }
        String value;

        try {
            log.info("Loading ad {}", url);
            var doc = Jsoup.connect(url).get();
            var attributesMap = new LinkedHashMap<String, String>();
            //"Published:" -> "23.02.2021"
            value = Objects.requireNonNull(doc.select("span:matchesOwn(^Published$)").first())
                    .parent().parent().select("span").last().text();
            attributesMap.put("Published", value);
            //"Updated:" -> "25.11.2024"
            value = Optional.ofNullable(doc.select("span:matchesOwn(^Updated$)").first())
                    .map(e -> e.parent().parent().select("span").last().text())
                    .orElse(null);
            attributesMap.put("Updated", value);
            //"Neighborhood:" -> "Zabjelo"
            value = doc.select("h3:contains(Location)").first()
                    .parent().select("p.text-base.font-semibold").first().text();
            attributesMap.put("Neighborhood", value);
            //"Price:" -> "180,000€"
            value = doc.select("h3:contains(Price)").first()
                    .parent().selectFirst("p.font-bold").text().replaceAll("\\s+", "");
            attributesMap.put("Price", value);
            //"Area:" -> "87m²"
            value = doc.select("span:matchesOwn(^Area$)").first()
                    .parent().parent().selectFirst("p.font-semibold").text();
            attributesMap.put("Area", value);
            //"Rooms" -> "3"
            value = doc.select("span:matchesOwn(^Rooms$)").first()
                    .parent().parent().selectFirst("p.font-semibold").text();
            attributesMap.put("Rooms", value);
            //"City:" -> "Podgorica"
            value = doc.select("h3:matchesOwn(^Location$)").first()
                    .parent().select("p.text-sm").first().text().replaceAll(".*,\\s*", "");
            attributesMap.put("City", value);

            var h1Text = doc.select("h1").first().text();
            var parts = h1Text.split(",");

            attributesMap.put("Type", parts[0].trim() + " " + parts[1].trim());
            return attributesMap;
        } catch (Exception e) {
            log.error("Can't load ad {}", url, e);
            Thread.sleep(1000);
            return loadAdAttributes(url, repeats - 1);
        }
    }

    /**
     * load and save ad by id to db
     *
     * @param url
     * @param repeats
     */
    private AdEntity loadAdAndSave(String url, int repeats) {
        var id = url.replaceAll(".*/id-(\\d+)", "$1");

        try {
            if (repeats < 0) {
                log.error("Will be not repeat for {}", url);
                return null;
            }
            //TODO Тут что-то не понятное.. Надо посмотреть
            var adEntity = adRepository.findBySourceIdAndSourceCode(id, "estitor");
            var attributesMap = loadAdAttributes(url, 1);
            if (attributesMap == null || attributesMap.isEmpty()) {
                log.error("Attributes is empty for {}. Stun will be skipped, not founded in DB", id);
                return null;
            }

            var lastModifiedStr = Optional.ofNullable(attributesMap.get("Updated"))
                    .orElse(attributesMap.get("Published"));
            var lastModified = switch (lastModifiedStr) {
                case null -> null;
                default -> {
                    try {
                        yield LocalDate.parse(lastModifiedStr, DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.ENGLISH));
                    } catch (Exception e) {
                        log.error("Can't parse data {}, {}", id, attributesMap.get("Updated"), e);
                        yield null;
                    }
                }
            };

            if (lastModified != null && lastModified.isBefore(LocalDateTime.now().minusMonths(18).toLocalDate())) {
                log.info("Stun {} is deprecated", id);
                return null;
            }

            if (adEntity == null) {
                adEntity = new AdEntity();
                adEntity.setSourceId(id);
                adEntity.setSourceCode("estitor");
                adEntity.setSourceLink(url);
            }
            adEntity.setCity(attributesMap.get("City"));
            adEntity.setLocation(attributesMap.get("Neighborhood"));
            adEntity.setPrice(attributesMap.get("Price") != null ? attributesMap.get("Price").replaceAll("[^\\d]", "") : null);
            adEntity.setBedrooms(attributesMap.get("Rooms"));
            adEntity.setSize(attributesMap.get("Area") != null ? attributesMap.get("Area").replaceAll("[^\\d]", "") : null);
            adEntity.setLastModified(lastModified == null ? null : lastModified.atStartOfDay());
            adEntity.setType(convertType(attributesMap.get("Type")));
            adRepository.save(adEntity);
            log.info("Save stun {}", id);

            return adEntity;
        } catch (Exception e) {
            log.error("Can't save stun {}", id, e);
            return null;
        }
    }

    private AdEntity.Type convertType(String type) {
        if (StringUtils.containsIgnoreCase(type, "Sale")) {
            if (StringUtils.containsIgnoreCase(type, "Office")) {
                return COMMERCIAL_FOR_SALE;
            }
            if (StringUtils.containsIgnoreCase(type, "House")) {
                return HOUSE_FOR_SALE;
            }
            if (StringUtils.containsIgnoreCase(type, "Apartment") || StringUtils.containsIgnoreCase(type, "Studio")) {
                return APARTMENT_FOR_SALE;
            }
            if (StringUtils.containsIgnoreCase(type, "Land")) {
                return LAND_FOR_SALE;
            }
        }

        if (StringUtils.containsIgnoreCase(type, "Rent")) {
            if (StringUtils.containsIgnoreCase(type, "Office")) {
                return COMMERCIAL_LONG_TERM_RENTAL;
            }
            if (StringUtils.containsIgnoreCase(type, "Apartment") || StringUtils.containsIgnoreCase(type, "Studio")) {
                return APARTMENT_LONG_TERM_RENTAL;
            }
            if (StringUtils.containsIgnoreCase(type, "House")) {
                return HOUSE_LONG_TERM_RENTAL;
            }
            if (StringUtils.containsIgnoreCase(type, "Land")) {
                return LAND_LONG_TERM_RENTAL;
            }

        }
        return OTHER;
    }
}
