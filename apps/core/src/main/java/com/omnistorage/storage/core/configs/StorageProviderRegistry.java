package com.omnistorage.storage.core.configs;

import com.omnistorage.storage.core.adapters.AbstractStorageAdapter;
import com.omnistorage.storage.core.enums.StorageCapability;
import com.omnistorage.storage.core.enums.StorageProvider;
import com.omnistorage.storage.core.ports.StorageProviderInfo;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@AllArgsConstructor
public class StorageProviderRegistry {
    private final List<AbstractStorageAdapter> allAdapters;

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        allAdapters.forEach(AbstractStorageAdapter::validateProvider);
    }

    public <T extends StorageProviderInfo> T getPort(StorageProvider provider, Class<T> portType) {
        return allAdapters.stream()
                .filter(adapter -> adapter.getProvider() == provider)
                .filter(AbstractStorageAdapter::isActive)
                .filter(portType::isInstance)
                .map(portType::cast)
                .findFirst()
                .orElseThrow(() -> new RuntimeException(String.valueOf(provider)));
    }

    public Set<StorageCapability> getCapabilitiesOf(StorageProvider provider) {
        return allAdapters.stream()
                .filter(a -> a.getProvider() == provider)
                .map(AbstractStorageAdapter::getCapabilities)
                .findFirst()
                .orElse(Collections.emptySet());
    }
}
