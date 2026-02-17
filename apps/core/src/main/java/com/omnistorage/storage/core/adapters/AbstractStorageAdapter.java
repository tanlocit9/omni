package com.omnistorage.storage.core.adapters;

import com.omnistorage.storage.core.enums.StorageCapability;
import com.omnistorage.storage.core.ports.DeletablePort;
import com.omnistorage.storage.core.ports.ListablePort;
import com.omnistorage.storage.core.ports.ReadablePort;
import com.omnistorage.storage.core.ports.ShareablePort;
import com.omnistorage.storage.core.ports.StorageProviderInfo;
import com.omnistorage.storage.core.ports.WritablePort;

import java.util.HashSet;
import java.util.Set;

public abstract class AbstractStorageAdapter implements StorageProviderInfo {

    protected boolean isActive = false;
    protected String lastErrorMessage;

    protected abstract void doValidate() throws Exception;

    @Override
    public final void validateProvider() {
        try {
            doValidate();
            this.isActive = true;
            this.lastErrorMessage = null;
        } catch (Exception e) {
            this.isActive = false;
            this.lastErrorMessage = e.getMessage();
            getLog().warn("⚠️ Provider [{}] khởi tạo thất bại: {}", getProvider(), e.getMessage());
        }
    }

    protected abstract org.slf4j.Logger getLog();

    public boolean isActive() {
        return isActive;
    }

    public Set<StorageCapability> getCapabilities() {
        Set<StorageCapability> caps = new HashSet<>();
        if (this instanceof ReadablePort) caps.add(StorageCapability.READ);
        if (this instanceof WritablePort) caps.add(StorageCapability.WRITE);
        if (this instanceof DeletablePort) caps.add(StorageCapability.DELETE);
        if (this instanceof ShareablePort) caps.add(StorageCapability.SHARE);
        if (this instanceof ListablePort) caps.add(StorageCapability.LIST);
        return caps;
    }
}