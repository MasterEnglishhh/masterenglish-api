package com.masterenglish.api.common.storage;

public class StorageObjectNotFoundException extends StorageException {
    public StorageObjectNotFoundException(String message) {
        super(message);
    }

    public StorageObjectNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
