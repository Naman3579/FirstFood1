"use client";

import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from "react";
import { CheckCircle2, AlertCircle, Info } from "lucide-react";

type Kind = "success" | "error" | "info";
type Item = { id: number; kind: Kind; text: string };
type ToastApi = { success: (t: string) => void; error: (t: string) => void; info: (t: string) => void };

const ToastContext = createContext<ToastApi | null>(null);
let nextId = 1;

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<Item[]>([]);

  const push = useCallback((kind: Kind, text: string) => {
    const id = nextId++;
    setItems((cur) => [...cur.slice(-2), { id, kind, text }]);
    setTimeout(() => setItems((cur) => cur.filter((i) => i.id !== id)), kind === "error" ? 6000 : 3500);
  }, []);

  const api = useMemo<ToastApi>(
    () => ({
      success: (t) => push("success", t),
      error: (t) => push("error", t),
      info: (t) => push("info", t),
    }),
    [push],
  );

  return (
    <ToastContext.Provider value={api}>
      {children}
      <div className="toasts" role="status" aria-live="polite">
        {items.map((i) => (
          <div key={i.id} className={`toast toast-${i.kind}`}>
            {i.kind === "success" ? <CheckCircle2 size={18} /> : i.kind === "error" ? <AlertCircle size={18} /> : <Info size={18} />}
            <span>{i.text}</span>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast(): ToastApi {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error("useToast must be used inside <ToastProvider>");
  return ctx;
}
