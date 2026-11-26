import { Spinner } from "@/components/ui";

export default function Loading() {
  return (
    <div style={{ minHeight: "60dvh", display: "grid", placeItems: "center", color: "var(--muted)" }}>
      <Spinner />
    </div>
  );
}
