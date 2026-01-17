package com.omnistorage.storage.core.ports;

import java.io.InputStream;

public interface WritablePort {

  void write(String path, InputStream data);
}
