package com.realitica.parser.service.loader;

import com.realitica.parser.repository.AdRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class LoaderService {
    private final List<IContentLoader> contentLoaders;
    private final AdRepository adRepository;

    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT2H")
    private void load() {
        contentLoaders.forEach(loader -> {
            log.info("Start loading from {}", loader.getSourceName());
            var ids = loader.loadAndSave();
            log.info("Finish loading from {}, count {}", loader.getSourceName(), ids.size());
        });
    }

    @Scheduled(cron = "0 0 0 * * MON") // every Monday
    private void removeDeprecated() {
        log.info("Start scheduler to  remove deprecated");
        var deprecatedDate = OffsetDateTime.now().minusMonths(2);
        var deprecatedAdEntities = adRepository.findAll().stream()
                .filter(s -> s.getUpdated() == null || s.getUpdated().isBefore(deprecatedDate))
                .filter(s -> resolveLoader(s.getSourceCode()).isCanBeDeleted(s.getSourceId()))
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
