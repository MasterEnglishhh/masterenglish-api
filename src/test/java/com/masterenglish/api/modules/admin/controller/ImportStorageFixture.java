package com.masterenglish.api.modules.admin.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.masterenglish.api.common.storage.MediaStorage;
import com.masterenglish.api.common.storage.StorageObjectNotFoundException;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

final class ImportStorageFixture {

    private ImportStorageFixture() {}

    static void stub(MediaStorage storage) {
        Map<String, byte[]> objects = new HashMap<>();
        when(storage.isAvailable()).thenReturn(true);
        when(storage.bucket()).thenReturn("test-import-bucket");
        doAnswer(
                        call -> {
                            objects.putIfAbsent(
                                    call.getArgument(0),
                                    Files.readAllBytes(call.<Path>getArgument(1)));
                            return null;
                        })
                .when(storage)
                .putIfAbsent(anyString(), any(), anyString(), anyString());
        when(storage.openStream(anyString()))
                .thenAnswer(
                        call -> {
                            byte[] bytes = objects.get(call.getArgument(0));
                            if (bytes == null) {
                                throw new StorageObjectNotFoundException("Input not stored", null);
                            }
                            return new ByteArrayInputStream(bytes);
                        });
    }
}
