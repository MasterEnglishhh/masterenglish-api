package com.masterenglish.api.modules.practice.repository;

import com.masterenglish.api.modules.practice.entity.GroupMaterial;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GroupMaterialRepository extends JpaRepository<GroupMaterial, UUID> {
    void deleteByGroupId(UUID groupId);

    List<GroupMaterial> findByGroupIdOrderByPositionAsc(UUID groupId);
}
