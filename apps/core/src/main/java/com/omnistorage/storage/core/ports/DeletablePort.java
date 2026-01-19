package com.omnistorage.storage.core.ports;

public interface DeletablePort extends StorageProviderInfo {
  void delete(String folderName, String objectName);
}
