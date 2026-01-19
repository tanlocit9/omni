package com.omnistorage.storage.core.ports;

import com.omnistorage.storage.core.enums.StorageProvider;

public interface StorageProviderInfo {
    StorageProvider getProvider();
    void validateProvider();
}
