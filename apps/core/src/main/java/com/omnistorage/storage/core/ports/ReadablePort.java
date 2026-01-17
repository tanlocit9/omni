package com.omnistorage.storage.core.ports;

import java.io.InputStream;

public interface ReadablePort {

  InputStream read(String path);
}
