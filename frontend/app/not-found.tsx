import Link from "next/link";
import { SearchX } from "lucide-react";

export default function NotFound() {
  return (
    <div className="page" style={{ alignItems: "center", textAlign: "center", paddingTop: "4rem" }}>
      <div className="empty-icon">
        <SearchX size={26} />
      </div>
      <h1>Page not found</h1>
      <p className="muted">That page doesn&apos;t exist or has moved.</p>
      <Link href="/" className="btn btn-primary">
        Go home
      </Link>
    </div>
  );
}
