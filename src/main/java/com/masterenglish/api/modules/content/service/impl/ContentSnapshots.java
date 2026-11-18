package com.masterenglish.api.modules.content.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import org.springframework.stereotype.Component;

@Component
class ContentSnapshots {
    static final short SCHEMA_VERSION = 1;

    private final ObjectMapper mapper;

    ContentSnapshots(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    String envelope(String type, String snapshot) {
        JsonNode root = readObject(snapshot == null ? "{}" : snapshot);
        if ((root.has("schema_version") && root.path("schema_version").asInt() != SCHEMA_VERSION)
                || (root.has("content_type") && !root.path("content_type").asText().equals(type))) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "Snapshot envelope does not match the item.");
        }

        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("schema_version", SCHEMA_VERSION);
        envelope.put("content_type", type);
        JsonNode data = dataOf(root);
        if (data instanceof ObjectNode object) {
            object.remove("schema_version");
            object.remove("content_type");
        }
        envelope.set("data", data);
        return envelope.toString();
    }

    JsonNode data(String snapshot) {
        return dataOf(readObject(snapshot));
    }

    private JsonNode readObject(String snapshot) {
        try {
            JsonNode root = mapper.readTree(snapshot);
            if (root != null && root.isObject()) return root;
        } catch (JsonProcessingException exception) {
            throw invalidSnapshot();
        }
        throw invalidSnapshot();
    }

    private static JsonNode dataOf(JsonNode root) {
        return root.has("data") ? root.path("data") : root;
    }

    private static BusinessException invalidSnapshot() {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, "Invalid snapshot JSON.");
    }
}
