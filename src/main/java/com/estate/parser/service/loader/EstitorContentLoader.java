package com.estate.parser.service.loader;

import com.estate.parser.entity.AdEntity;
import com.estate.parser.repository.AdRepository;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.StreamSupport;

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
                baseUrl + "/me-en/real-estates/purpose-rent",
                baseUrl + "/me-en/real-estates/purpose-sale"
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

    @SneakyThrows
    private HashSet<String> loadIdsBySearch(String urlWithAds) {
        log.info("Start to load by filter: {}", urlWithAds);
        var links = new LinkedHashSet<String>();

        int curPage = 1;
        while (curPage >= 1) {
            try {
                var url = urlWithAds + (curPage > 1 ? "/page-" + curPage : "");
                var pageDoc = jsoupGet(url, 3);
                if (pageDoc == null) {
                    log.error("Failed to load page {} of {}, stopping", curPage, urlWithAds);
                    break;
                }
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
            var doc = jsoupGet(url, 3);
            if (doc == null) {
                return null;
            }
            var attributesMap = new LinkedHashMap<String, String>();
            var mapper = new ObjectMapper();
            String jsonLd = doc.select("script[type=application/ld+json]")
                    .stream()
                    .map(Element::html)
                    .filter(text -> text.contains("\"RealEstateListing\""))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("RealEstateListing JSON-LD not found"));

            JsonNode root = mapper.readTree(jsonLd);
            JsonNode listing = StreamSupport.stream(root.path("@graph").spliterator(), false)
                    .filter(node -> "RealEstateListing".equals(node.path("@type").asText()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("RealEstateListing node not found"));

            JsonNode offer = listing.path("offers");
            JsonNode item = offer.path("itemOffered");


            attributesMap.put("Published", listing.path("datePosted").asText(null));
            attributesMap.put("Updated", listing.path("dateModified").asText(null));

            attributesMap.put("Price", offer.path("price").asText(null));

            attributesMap.put("Area", item.path("floorSize").path("value").asText(null) + "m²");

            attributesMap.put("Rooms", item.path("numberOfRooms").asText(null));

            JsonNode address = item.path("address");
            attributesMap.put("City", address.path("addressLocality").asText(null));

            attributesMap.put("Neighborhood", extractNeighborhoodFromName(listing.path("name").asText(null)));

            attributesMap.put("Type", extractTypeFromName(listing.path("name").asText(null)));

            return attributesMap;
        } catch (Exception e) {
            log.error("Can't load ad {}", url, e);
            Thread.sleep(1000);
            return loadAdAttributes(url, repeats - 1);
        }
    }

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
                        yield LocalDate.parse(lastModifiedStr, DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH));
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

    private long lastRequestTime = 0;
    private static final long RATE_LIMIT_MS = 1000;
    private static final long RATE_LIMIT_JITTER_MS = 1000;

    @SneakyThrows
    private synchronized Document jsoupGet(String url, int attempts) {
        if (attempts <= 0) {
            log.error("Exceeded max attempts to load page {}", url);
            return null;
        }

        long now = System.currentTimeMillis();
        long jitter = (long) (Math.random() * RATE_LIMIT_JITTER_MS);
        long wait = lastRequestTime + RATE_LIMIT_MS + jitter - now;
        if (wait > 0) {
            Thread.sleep(wait);
        }
        lastRequestTime = System.currentTimeMillis();

        try {
            return Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .timeout(30_000)
                    .get();
        } catch (IOException e) {
            log.warn("Can't load page {}, attempts left {}", url, attempts - 1, e);
            Thread.sleep(3_000);
            return jsoupGet(url, attempts - 1);
        }
    }

    private String extractTypeFromName(String name) {
        if (name == null) {
            return null;
        }

        // "Rent, office space, 115m², Blok 9, Podgorica"
        var parts = name.split(",");

        if (parts.length < 2) {
            return name;
        }

        return parts[0].trim() + " " + parts[1].trim();
    }

    private String extractNeighborhoodFromName(String name) {
        if (name == null) {
            return null;
        }

        // "Rent, office space, 115m², Blok 9, Podgorica"
        var parts = name.split(",");

        if (parts.length < 4) {
            return null;
        }

        return parts[3].trim();
    }
}
