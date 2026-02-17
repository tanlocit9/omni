package com.omnistorage.storage.core.ports;

import java.io.InputStream;

public interface ReadablePort extends StorageProviderInfo {
  InputStream read(String folderName, String objectName);
}
