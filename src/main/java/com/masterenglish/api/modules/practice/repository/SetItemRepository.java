package com.masterenglish.api.modules.practice.repository;

import com.masterenglish.api.modules.practice.entity.SetItem;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SetItemRepository extends JpaRepository<SetItem, UUID> {
    void deleteBySetId(UUID setId);

    List<SetItem> findBySetIdOrderByPositionAsc(UUID setId);
}
