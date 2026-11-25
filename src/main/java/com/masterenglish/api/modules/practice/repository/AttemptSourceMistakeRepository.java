package com.masterenglish.api.modules.practice.repository;

import com.masterenglish.api.modules.practice.entity.AttemptSourceMistake;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AttemptSourceMistakeRepository
        extends JpaRepository<AttemptSourceMistake, AttemptSourceMistake.Id> {}
