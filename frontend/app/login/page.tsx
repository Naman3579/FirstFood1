import { redirect } from "next/navigation";

/** The old single sign-in page. There are two portals now: customers at "/", staff at "/staff". */
export default function LegacyLogin() {
  redirect("/");
}
