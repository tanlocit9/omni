package com.omni.platform.core.ports;

import com.omni.platform.core.enums.StorageProvider;

public interface StorageProviderInfo {
    StorageProvider getProvider();
    void validateProvider();
}
