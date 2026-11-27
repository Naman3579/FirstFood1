import { apiFetch } from "./api-client";

export type VersionInfo = { service: string; apiVersion: string };

/** Public endpoint (no token). Used by the footer/health indicator so support can see which API the app talks to. */
export const systemApi = {
  version(): Promise<VersionInfo> {
    return apiFetch<VersionInfo>("/version");
  },
};
