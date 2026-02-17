package com.omni.platform.core.ports;

import java.io.InputStream;

public interface ReadablePort extends StorageProviderInfo {
  InputStream read(String folderName, String objectName);
}
