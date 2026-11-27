"use client";

import { Field } from "@/components/ui";

/**
 * Indian-first phone entry: type 10 digits, we send +91…. A pasted "+…" number is kept as typed, because staff may list
 * people by phone; but anything that must RECEIVE an SMS (sign-in, phone change) is checked against the India-only SMS
 * rule (F-05) by its caller with isSmsSupportedPhone.
 */
export function PhoneField({
  label = "Phone number",
  value,
  onChange,
  error,
  autoFocus,
  hint,
}: {
  label?: string;
  value: string;
  onChange: (v: string) => void;
  error?: string | null;
  autoFocus?: boolean;
  hint?: string;
}) {
  return (
    <Field label={label} error={error} hint={hint}>
      {(id) => (
        <div className="input-group">
          <span className="input-prefix" aria-hidden="true">
            +91
          </span>
          <input
            id={id}
            className="input"
            type="tel"
            inputMode="tel"
            autoComplete="tel-national"
            autoFocus={autoFocus}
            placeholder="98765 43210"
            value={value}
            aria-invalid={error ? true : undefined}
            onChange={(e) => onChange(e.target.value.replace(/[^\d+\s]/g, ""))}
          />
        </div>
      )}
    </Field>
  );
}
