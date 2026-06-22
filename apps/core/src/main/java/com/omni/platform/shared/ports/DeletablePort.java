package com.omni.platform.shared.ports;

public interface DeletablePort extends StorageProviderInfo {
  void delete(String folderName, String objectName);
}
