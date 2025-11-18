package com.estate.parser.service.loader;

import com.estate.parser.repository.AdRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class LoaderService {
    private final List<IContentLoader> contentLoaders;
    private final AdRepository adRepository;

    @Scheduled(cron = "0 0 19 * * *") // every day at 19:00
    private void load() {
        contentLoaders.parallelStream().forEach(loader -> {
            log.info("Start loading from {}", loader.getSourceName());
            var ids = loader.loadAndSave();
            log.info("Finish loading from {}, count {}", loader.getSourceName(), ids.size());
        });
    }

    @Scheduled(cron = "0 0 18 * * SUN") // every Sunday at 18:00
    private void removeDeprecated() {
        log.info("Start scheduler to  remove deprecated");
        var deprecatedDate = OffsetDateTime.now().minusMonths(2);
        var toRemoveDate = LocalDateTime.now().minusMonths(9);
        var deprecatedAdEntities = adRepository.findAll().stream()
                .filter(s -> s.getUpdated() == null || s.getUpdated().isBefore(deprecatedDate))
                .filter(s -> {
                    if(s.getLastModified() != null && s.getLastModified().isBefore(toRemoveDate)){
                        log.info("Stun {} is deprecated", s.getId());
                        return true;
                    }
                    return resolveLoader(s.getSourceCode()).isCanBeDeleted(s.getSourceId());
                })
                .collect(Collectors.toList());
        adRepository.deleteAll(deprecatedAdEntities);
    }

    private IContentLoader resolveLoader(String sourceCode) {
        return contentLoaders.stream()
                .filter(l -> l.getSourceName().equals(sourceCode))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Loader not found for " + sourceCode));
    }

}
