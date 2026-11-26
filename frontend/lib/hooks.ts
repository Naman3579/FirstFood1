"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError } from "./api-client";
import { useSession } from "./session";
import { useToast } from "@/components/ui/toast";

export function errorMessage(err: unknown): string {
  if (err instanceof ApiError) return err.message;
  return err instanceof Error ? err.message : "Something went wrong.";
}

export type Query<T> = {
  data: T | undefined;
  error: string | null;
  loading: boolean;
  /** Refetch without blanking the screen. */
  reload: () => Promise<void>;
  setData: (next: T) => void;
};

/**
 * Fetch on mount and whenever `deps` change. Stale responses are dropped, existing data stays on screen during a
 * refetch (no flicker), and `enabled: false` skips the call (e.g. while a parent id is still unknown).
 */
export function useQuery<T>(
  fn: (accessToken: string) => Promise<T>,
  deps: readonly unknown[],
  options: { enabled?: boolean } = {},
): Query<T> {
  const { call } = useSession();
  const enabled = options.enabled ?? true;
  const [data, setData] = useState<T | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(enabled);
  const fnRef = useRef(fn);
  fnRef.current = fn;
  const seq = useRef(0);

  const run = useCallback(async () => {
    const mine = ++seq.current;
    setLoading(true);
    try {
      const result = await call((t) => fnRef.current(t));
      if (mine !== seq.current) return;
      setData(result);
      setError(null);
    } catch (err) {
      if (mine !== seq.current) return;
      setError(errorMessage(err));
    } finally {
      if (mine === seq.current) setLoading(false);
    }
  }, [call]);

  useEffect(() => {
    if (!enabled) {
      setLoading(false);
      return;
    }
    setData(undefined);
    void run();
  }, [enabled, run, ...deps]);

  return { data, error, loading, reload: run, setData };
}

/**
 * Wrap a mutation: busy flag, error surfaced as a toast (or returned to the caller), optional success toast.
 * Returns the result, or undefined when it failed.
 */
export function useAction() {
  const { call } = useSession();
  const toast = useToast();
  const [busy, setBusy] = useState(false);

  const run = useCallback(
    async <T,>(fn: (accessToken: string) => Promise<T>, opts: { success?: string; quiet?: boolean } = {}): Promise<T | undefined> => {
      setBusy(true);
      try {
        const result = await call(fn);
        if (opts.success) toast.success(opts.success);
        return result;
      } catch (err) {
        if (!opts.quiet) toast.error(errorMessage(err));
        return undefined;
      } finally {
        setBusy(false);
      }
    },
    [call, toast],
  );

  return { run, busy };
}
