import { BellRing, CalendarCheck2, ChefHat, ClipboardList, RefreshCw, ShieldCheck, Soup, Users, type LucideIcon } from "lucide-react";
import type { Portal } from "@/lib/auth-api";

/**
 * Everything that makes the two sign-in screens different: wording, side art, where "the other door" is. The flow
 * itself (components/auth/PortalAuth.tsx) is shared, so a fix to one portal is a fix to both.
 */
export type PortalCopy = {
  /** Small label above the form, so nobody is ever unsure which door they are at. */
  chip: string;
  icon: LucideIcon;
  home: string;
  art: { title: string; points: { icon: LucideIcon; text: string }[]; foot: string };
  signin: { title: string; sub: string; cta: string };
  signup: { title: string; sub: string; cta: string };
  name: { label: string; placeholder: string; hint: string };
  /** Shown when sign-in finds no account on this portal and switches to "Create account". */
  notRegistered: string;
  /** Shown when sign-up finds an existing account and signs the person in instead. */
  alreadyRegistered: string;
  /** Shown to someone already signed in on the other portal, who has no registration here yet. */
  addPortal: string;
};

export const PORTAL_COPY: Record<Portal, PortalCopy> = {
  customer: {
    chip: "Customer portal",
    icon: Soup,
    home: "/",
    art: {
      title: "Home-style meals, without the paperwork.",
      points: [
        { icon: CalendarCheck2, text: "Skip a meal in two taps" },
        { icon: BellRing, text: "Know exactly how many days are left" },
        { icon: ShieldCheck, text: "Every mess you eat at, in one place" },
      ],
      foot: "Mess · Tiffin · PG · Hostel",
    },
    signin: { title: "Welcome back", sub: "Sign in with your email or mobile number to see your plan.", cta: "Sign in" },
    signup: { title: "Create your account", sub: "Join with your email or mobile number to follow your meals, absences and renewals.", cta: "Create account" },
    name: { label: "Your name", placeholder: "e.g. Rahul Sharma", hint: "Your mess sees this name on its customer list." },
    notRegistered: "There's no customer account for this email or number yet. Add your name to create one - the code you entered is still valid.",
    alreadyRegistered: "You already have a customer account, so we're signing you in.",
    addPortal: "This account isn't set up as a customer yet. Create a customer account to see your meals.",
  },
  staff: {
    chip: "Staff portal",
    icon: ChefHat,
    home: "/staff",
    art: {
      title: "Run your kitchen from one screen.",
      points: [
        { icon: ClipboardList, text: "Today's attendance, corrected in a tap" },
        { icon: RefreshCw, text: "Plans and renewals, never missed" },
        { icon: Users, text: "Your team, each with the right access" },
      ],
      foot: "For owners, managers and kitchen staff",
    },
    signin: { title: "Staff sign in", sub: "Sign in with the email or mobile number registered for your kitchen team.", cta: "Sign in" },
    signup: { title: "Create a staff account", sub: "Register as owner or team member. You can set up your first kitchen right after.", cta: "Create staff account" },
    name: { label: "Your name", placeholder: "e.g. Riya Khandelwal", hint: "Your team sees this name in the team list." },
    notRegistered: "There's no staff account for this email or number yet. Add your name to create one - the code you entered is still valid.",
    alreadyRegistered: "You already have a staff account, so we're signing you in.",
    addPortal: "This account isn't registered for staff yet. Create a staff account to manage a kitchen.",
  },
};
