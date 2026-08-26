/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly SYSTEM_OPERATOR_UUID: string;
  readonly VITE_QUERY_SERVICE_URL?: string;
  readonly VITE_PLATFORM_API_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
