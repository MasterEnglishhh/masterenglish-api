package com.masterenglish.api.modules.admin.repository;

import com.masterenglish.api.modules.admin.entity.FeatureFlag;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeatureFlagRepository extends JpaRepository<FeatureFlag, String> {

    List<FeatureFlag> findAllByOrderByCategoryAscKeyAsc();
}
