package com.masterenglish.api.common.storage;

public class StorageDisabledException extends StorageException {
    public StorageDisabledException(String message) {
        super(message);
    }

    public StorageDisabledException(String message, Throwable cause) {
        super(message, cause);
    }
}
