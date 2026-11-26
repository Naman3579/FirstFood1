"use client";

import { useState, type FormEvent } from "react";
import { LocateFixed, MapPinOff } from "lucide-react";
import { Field, Spinner } from "@/components/ui";
import { PhoneField } from "./PhoneInput";
import { FOOD_TYPE_LABEL, PROVIDER_TYPE_LABEL } from "./status";
import {
  FOOD_TYPES,
  PROVIDER_TYPES,
  type CreateProviderInput,
  type FoodType,
  type Provider,
  type ProviderType,
} from "@/lib/provider-api";
import { isValidPhone, normalizePhone } from "@/lib/format";

export type ProviderFormValues = CreateProviderInput & { clearLocation?: boolean };

/** Shared by "register a provider" and "edit provider". Light client checks only - the server validates for real. */
export function ProviderForm({
  initial,
  submitLabel,
  busy,
  onSubmit,
  onCancel,
}: {
  initial?: Provider;
  submitLabel: string;
  busy: boolean;
  onSubmit: (v: ProviderFormValues) => void;
  onCancel?: () => void;
}) {
  const [name, setName] = useState(initial?.name ?? "");
  const [providerTypes, setTypes] = useState<ProviderType[]>(initial?.providerTypes ?? ["MESS"]);
  const [description, setDescription] = useState(initial?.description ?? "");
  const [addressLine, setAddress] = useState(initial?.addressLine ?? "");
  const [locality, setLocality] = useState(initial?.locality ?? "");
  const [city, setCity] = useState(initial?.city ?? "");
  const [pincode, setPincode] = useState(initial?.pincode ?? "");
  const [phone, setPhone] = useState(initial?.contactPhone?.replace(/^\+91/, "") ?? "");
  const [foodType, setFoodType] = useState<FoodType>(initial?.foodType ?? "VEG_AND_NON_VEG");
  // The pinned spot customers' "near me" search measures from. `null` = none; `changed` = differs from what is saved.
  const [pin, setPin] = useState<{ lat: number; lng: number } | null>(
    initial?.latitude != null && initial?.longitude != null ? { lat: initial.latitude, lng: initial.longitude } : null,
  );
  const [pinChanged, setPinChanged] = useState(false);
  const [pinState, setPinState] = useState<"idle" | "locating" | "failed">("idle");
  const [errors, setErrors] = useState<Record<string, string>>({});

  function pinHere() {
    if (typeof navigator === "undefined" || !navigator.geolocation) {
      setPinState("failed");
      return;
    }
    setPinState("locating");
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        // 6 decimals (~10 cm) is what the server stores.
        setPin({ lat: Math.round(pos.coords.latitude * 1e6) / 1e6, lng: Math.round(pos.coords.longitude * 1e6) / 1e6 });
        setPinChanged(true);
        setPinState("idle");
      },
      () => setPinState("failed"),
      { enableHighAccuracy: true, timeout: 15_000 },
    );
  }

  function submit(e: FormEvent) {
    e.preventDefault();
    const next: Record<string, string> = {};
    if (!name.trim()) next.name = "Required";
    if (!addressLine.trim()) next.addressLine = "Required";
    if (!locality.trim()) next.locality = "Required";
    if (!city.trim()) next.city = "Required";
    if (pincode && !/^[1-9][0-9]{5}$/.test(pincode)) next.pincode = "6 digits";
    const contact = phone.trim() ? normalizePhone(phone) : "";
    if (contact && !isValidPhone(contact)) next.phone = "Enter a valid number";
    if (providerTypes.length === 0) next.providerTypes = "Choose at least one";
    setErrors(next);
    if (Object.keys(next).length) return;
    onSubmit({
      name: name.trim(),
      providerTypes,
      description: description.trim() || undefined,
      addressLine: addressLine.trim(),
      locality: locality.trim(),
      city: city.trim(),
      pincode: pincode || undefined,
      contactPhone: contact || undefined,
      foodType,
      ...(pin && pinChanged ? { latitude: pin.lat, longitude: pin.lng } : {}),
      ...(!pin && pinChanged ? { clearLocation: true } : {}),
    });
  }

  return (
    <form className="stack" onSubmit={submit} noValidate>
      <div className="form-grid">
        <Field label="Name" error={errors.name}>
          {(id) => <input id={id} className="input" maxLength={120} value={name} onChange={(e) => setName(e.target.value)} placeholder="Annapurna Mess" autoFocus />}
        </Field>
        <Field label="Food served">
          {(id) => (
            <select id={id} className="select" value={foodType} onChange={(e) => setFoodType(e.target.value as FoodType)}>
              {FOOD_TYPES.map((t) => (
                <option key={t} value={t}>
                  {FOOD_TYPE_LABEL[t]}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="Type" hint="Choose every kind that applies, e.g. Mess and Tiffin." error={errors.providerTypes}>
          {() => (
            <div className="row" style={{ gap: "0.4rem" }} role="group" aria-label="Provider types">
              {PROVIDER_TYPES.map((t) => {
                const on = providerTypes.includes(t);
                return (
                  <button
                    key={t}
                    type="button"
                    className={on ? "btn btn-primary btn-sm" : "btn btn-ghost btn-sm"}
                    aria-pressed={on}
                    onClick={() => setTypes(on ? providerTypes.filter((x) => x !== t) : [...providerTypes, t])}
                  >
                    {PROVIDER_TYPE_LABEL[t]}
                  </button>
                );
              })}
            </div>
          )}
        </Field>
      </div>
      <Field label="Description" hint="Shown to customers. Optional.">
        {(id) => <textarea id={id} className="textarea" maxLength={1000} value={description} onChange={(e) => setDescription(e.target.value)} />}
      </Field>
      <Field label="Address" error={errors.addressLine}>
        {(id) => <input id={id} className="input" maxLength={255} value={addressLine} onChange={(e) => setAddress(e.target.value)} autoComplete="street-address" />}
      </Field>
      <div className="form-grid">
        <Field label="Locality" error={errors.locality}>
          {(id) => <input id={id} className="input" maxLength={120} value={locality} onChange={(e) => setLocality(e.target.value)} />}
        </Field>
        <Field label="City" error={errors.city}>
          {(id) => <input id={id} className="input" maxLength={100} value={city} onChange={(e) => setCity(e.target.value)} />}
        </Field>
        <Field label="Pincode" error={errors.pincode}>
          {(id) => <input id={id} className="input" inputMode="numeric" maxLength={6} value={pincode} onChange={(e) => setPincode(e.target.value.replace(/\D/g, ""))} />}
        </Field>
      </div>
      <Field
        label="Map location"
        hint="Customers searching near them find you by this pin. Stand at the kitchen and tap the button."
        error={pinState === "failed" ? "Couldn't get the location. Allow location access and try again." : undefined}
      >
        {() => (
          <div className="row" style={{ gap: "0.6rem" }}>
            <button type="button" className="btn btn-sm" onClick={pinHere} disabled={pinState === "locating"}>
              {pinState === "locating" ? <Spinner /> : <LocateFixed size={16} />}
              {pin ? "Update pin to where I am" : "Pin my current location"}
            </button>
            {pin ? (
              <>
                <span className="small muted">
                  {pin.lat.toFixed(4)}, {pin.lng.toFixed(4)}
                </span>
                <button
                  type="button"
                  className="btn btn-sm btn-ghost"
                  onClick={() => {
                    setPin(null);
                    setPinChanged(true);
                  }}
                >
                  <MapPinOff size={16} /> Remove
                </button>
              </>
            ) : (
              <span className="small muted">No pin yet - found by city only.</span>
            )}
          </div>
        )}
      </Field>
      <PhoneField label="Contact phone (optional)" value={phone} onChange={setPhone} error={errors.phone} />
      <div className="modal-actions">
        {onCancel ? (
          <button type="button" className="btn" onClick={onCancel}>
            Cancel
          </button>
        ) : null}
        <button className="btn btn-primary" disabled={busy}>
          {busy ? <Spinner /> : null}
          {submitLabel}
        </button>
      </div>
    </form>
  );
}
