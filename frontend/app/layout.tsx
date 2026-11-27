import type { Metadata, Viewport } from "next";
import type { ReactNode } from "react";
import "@fontsource-variable/inter";
import "@fontsource-variable/plus-jakarta-sans";
import "./globals.css";
import { SessionProvider } from "@/lib/session";
import { ToastProvider } from "@/components/ui/toast";

export const metadata: Metadata = {
  title: { default: "FirstFood", template: "%s · FirstFood" },
  description: "Daily meals, simplified. Subscriptions, attendance and renewals for messes, tiffin services and hostels.",
  applicationName: "FirstFood",
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  viewportFit: "cover",
  themeColor: "#00652c",
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <body>
        <SessionProvider>
          <ToastProvider>{children}</ToastProvider>
        </SessionProvider>
      </body>
    </html>
  );
}
