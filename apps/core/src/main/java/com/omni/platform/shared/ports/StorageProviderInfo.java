package com.omni.platform.shared.ports;

import com.omni.platform.shared.enums.StorageProvider;

public interface StorageProviderInfo {
    StorageProvider getProvider();
    void validateProvider();
}
