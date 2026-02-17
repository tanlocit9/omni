package com.omni.platform.core.ports;

import java.io.InputStream;

public interface WritablePort extends StorageProviderInfo {
  void write(String folderName, String objectName, InputStream data, String contentType);
}
