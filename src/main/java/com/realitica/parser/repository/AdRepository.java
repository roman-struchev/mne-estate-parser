package com.realitica.parser.repository;

import com.realitica.parser.entity.AdEntity;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.time.LocalDateTime;
import java.util.List;

@RepositoryRestResource(path = "ads")
public interface AdRepository extends JpaRepository<AdEntity, Long> {
    AdEntity findBySourceIdAndSourceCode(String id, String code);

    List<AdEntity> findAllByTypeIn(List<AdEntity.Type> types, Sort sort);

    List<AdEntity> findAllByLastModifiedGreaterThanEqual(LocalDateTime lastModifiedFrom, Sort sort);
}