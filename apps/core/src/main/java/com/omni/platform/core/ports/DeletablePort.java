package com.omni.platform.core.ports;

public interface DeletablePort extends StorageProviderInfo {
  void delete(String folderName, String objectName);
}
