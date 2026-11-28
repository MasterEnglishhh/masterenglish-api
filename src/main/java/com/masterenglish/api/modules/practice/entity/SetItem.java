package com.masterenglish.api.modules.practice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Entity
@Table(name = "set_items")
public class SetItem {

    @Id private UUID id;

    @Column(name = "set_id", nullable = false)
    private UUID setId;

    @Column(nullable = false)
    private int position;

    @Column(name = "question_id")
    private UUID questionId;

    @Column(name = "group_id")
    private UUID groupId;
}
