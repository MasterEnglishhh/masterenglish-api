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
@Table(name = "group_materials")
public class GroupMaterial {

    @Id private UUID id;

    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @Column(nullable = false)
    private int position;

    @Column(name = "document_key", length = 80)
    private String documentKey;

    @Column(name = "material_type", nullable = false, length = 8)
    private String materialType;

    @Column(name = "usage_role", nullable = false, length = 20)
    private String usageRole;

    @Column(name = "media_id")
    private UUID mediaId;

    @Column(columnDefinition = "TEXT")
    private String body;
}
