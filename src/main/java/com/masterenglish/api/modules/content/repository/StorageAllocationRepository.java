package com.masterenglish.api.modules.content.repository;

import com.masterenglish.api.modules.content.entity.StorageAllocation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StorageAllocationRepository extends JpaRepository<StorageAllocation, UUID> {

    Optional<StorageAllocation> findByAllocationKey(String allocationKey);

    java.util.List<StorageAllocation> findAllByUploadRequestId(UUID uploadRequestId);
}
