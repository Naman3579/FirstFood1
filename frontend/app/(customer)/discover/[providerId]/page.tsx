"use client";

import { use, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { CalendarDays, MapPin, Phone, Utensils } from "lucide-react";
import { Badge, Banner, BackLink, Boundary, Empty, Field, Modal, Spinner } from "@/components/ui";
import { priceRange, unavailableText } from "@/components/domain/ProviderCardView";
import { FOOD_TYPE_LABEL, PROVIDER_TYPE_LABEL } from "@/components/domain/status";
import { useAccount } from "@/components/shell/account";
import { useToast } from "@/components/ui/toast";
import { attachIntendedPayment, hasErrors, intentErrors, newIntent, PaymentChoice, type PaymentIntent } from "@/components/domain/PaymentBits";
import { discoveryApi, formatDistance, type PlanListing, type ProviderDetail } from "@/lib/discovery-api";
import { useAction, useQuery } from "@/lib/hooks";
import { newIdempotencyKey } from "@/lib/payment-api";
import { useLocation } from "@/lib/location";
import { membershipApi } from "@/lib/membership-api";
import { describePolicy, describeQuantity } from "@/lib/plan-api";
import { useSession } from "@/lib/session";
import { formatMoney, maskPhone, todayInIndia } from "@/lib/format";

function SubscribeModal({
  detail,
  plan,
  onClose,
}: {
  detail: ProviderDetail;
  plan: PlanListing;
  onClose: () => void;
}) {
  const router = useRouter();
  const { person } = useAccount();
  const { profile } = useSession();
  const { run, busy } = useAction();
  const [start, setStart] = useState(todayInIndia());
  const needsName = person.data === null;
  const [name, setName] = useState(profile?.fullName ?? "");
  const toast = useToast();
  const [intent, setIntent] = useState<PaymentIntent>(() => newIntent(plan.price));
  const [showErrors, setShowErrors] = useState(false);
  // One key per dialog: a double tap or a retry returns the same payment instead of creating a second.
  const paymentKey = useRef(newIdempotencyKey());
  const errors = intentErrors(intent, plan.price, plan.currency);

  async function confirm() {
    // Check the payment details first, so nothing is saved (not even the name) while the form is still wrong.
    setShowErrors(true);
    if (hasErrors(errors)) return;
    // First purchase from a brand-new account: the server needs the person's name (it is what providers see).
    if (needsName) {
      const saved = await run((t) => membershipApi.saveMyPerson(t, name.trim()));
      if (!saved) return;
      await person.reload();
    }
    const sub = await run((t) => discoveryApi.subscribe(t, detail.provider.id, { planId: plan.id, startDate: start }), {
      success: "You're subscribed.",
    });
    if (!sub) return;
    // The subscription exists now; the payment is attached to it. If that fails the subscription stays, and the
    // customer lands on its page where "Add payment" retries it.
    const paid = await attachIntendedPayment(run, sub.id, intent, paymentKey.current);
    if (!paid) toast.info("You're subscribed, but your payment wasn't saved. Add it below.");
    router.push(`/subscriptions/${sub.id}`);
  }

  return (
    <Modal title={`Subscribe to ${plan.name}`} onClose={onClose}>
      <p className="muted">
        {detail.provider.name} · {describeQuantity(plan)} · <span className="strong">{formatMoney(plan.price, plan.currency)}</span>
      </p>
      {needsName ? (
        <Field label="Your name" hint="Shown to the provider so they know who you are.">
          {(id) => <input id={id} className="input" value={name} maxLength={120} onChange={(e) => setName(e.target.value)} autoComplete="name" />}
        </Field>
      ) : null}
      <Field label="Start date" hint="Defaults to today. Past dates aren't allowed.">
        {(id) => <input id={id} type="date" className="input" value={start} min={todayInIndia()} onChange={(e) => setStart(e.target.value)} />}
      </Field>
      <ul className="small muted" style={{ paddingLeft: "1.1rem" }}>
        {describePolicy(plan).map((l) => (
          <li key={l}>{l}</li>
        ))}
      </ul>
      <p className="small faint">The price and terms above are locked in when you subscribe.</p>
      <PaymentChoice intent={intent} onChange={setIntent} errors={showErrors ? errors : {}} price={plan.price} currency={plan.currency} />
      <div className="modal-actions">
        <button className="btn" onClick={onClose} disabled={busy}>
          Cancel
        </button>
        <button className="btn btn-primary" onClick={() => void confirm()} disabled={busy || !start || (needsName && !name.trim())}>
          {busy ? <Spinner /> : null}
          Confirm subscription
        </button>
      </div>
    </Modal>
  );
}

export default function ProviderPage({ params }: { params: Promise<{ providerId: string }> }) {
  const { providerId } = use(params);
  const { location, ready } = useLocation();
  const detail = useQuery((t) => discoveryApi.get(t, providerId, location), [providerId, location?.lat, location?.lng], { enabled: ready });
  const [chosen, setChosen] = useState<PlanListing | null>(null);

  return (
    <main className="page" style={{ maxWidth: "48rem" }}>
      <BackLink href="/discover">Discover</BackLink>
      <Boundary query={detail} skeleton={<div className="skeleton" style={{ height: "12rem" }} />}>
        {(d) => {
          const p = d.provider;
          const distance = formatDistance(p.distanceKm);
          return (
            <>
              <div className="card stack">
                <div className="row between" style={{ alignItems: "flex-start", gap: "1rem" }}>
                  <div style={{ minWidth: 0 }}>
                    <h1>{p.name}</h1>
                    <div className="row" style={{ gap: "0.4rem", marginTop: "0.5rem" }}>
                      {p.providerTypes.map((t) => (
                        <Badge key={t} tone="accent">{PROVIDER_TYPE_LABEL[t] ?? t}</Badge>
                      ))}
                      <Badge>{FOOD_TYPE_LABEL[p.foodType] ?? p.foodType}</Badge>
                      <Badge tone={p.available ? "ok" : "warn"}>{p.available ? "Taking new customers" : unavailableText(p)}</Badge>
                    </div>
                  </div>
                  {p.activePlanCount > 0 ? <div className="strong" style={{ whiteSpace: "nowrap" }}>{priceRange(p)}</div> : null}
                </div>
                {p.description ? <p>{p.description}</p> : null}
                <div className="stack stack-s small">
                  <div className="row row-nowrap" style={{ alignItems: "flex-start" }}>
                    <MapPin size={16} aria-hidden style={{ marginTop: "0.15rem", flexShrink: 0 }} />
                    <span>
                      {p.addressLine}, {p.locality}, {p.city}
                      {p.pincode ? ` ${p.pincode}` : ""}
                      {distance ? <span className="muted"> · {distance} from you</span> : null}
                    </span>
                  </div>
                  {p.contactPhone ? (
                    <div className="row row-nowrap">
                      <Phone size={16} aria-hidden />
                      <a href={`tel:${p.contactPhone}`}>{maskPhone(p.contactPhone)}</a>
                    </div>
                  ) : null}
                  {p.latitude !== null && p.longitude !== null ? (
                    <a href={`https://www.google.com/maps/search/?api=1&query=${p.latitude},${p.longitude}`} target="_blank" rel="noopener noreferrer">
                      Open in maps
                    </a>
                  ) : null}
                </div>
              </div>

              <section className="stack stack-s">
                <h2>Plans</h2>
                {!p.available ? <Banner tone="warn">{unavailableText(p)} - you can look at the plans, but subscribing is switched off.</Banner> : null}
                {d.plans.length === 0 ? (
                  <div className="card">
                    <Empty icon={<Utensils size={26} />} title="No plans yet">
                      This provider hasn&apos;t published a plan. Check back soon.
                    </Empty>
                  </div>
                ) : (
                  <div className="stack">
                    {d.plans.map((plan) => (
                      <div key={plan.id} className="card stack stack-s">
                        <div className="row between" style={{ gap: "1rem", alignItems: "flex-start" }}>
                          <div style={{ minWidth: 0 }}>
                            <div className="strong">{plan.name}</div>
                            <div className="small muted row" style={{ gap: "0.3rem", marginTop: "0.15rem" }}>
                              <CalendarDays size={14} aria-hidden /> {describeQuantity(plan)}
                            </div>
                          </div>
                          <div className="strong" style={{ fontSize: "1.15rem", whiteSpace: "nowrap" }}>
                            {formatMoney(plan.price, plan.currency)}
                          </div>
                        </div>
                        {plan.description ? <p className="small muted">{plan.description}</p> : null}
                        <ul className="small muted" style={{ paddingLeft: "1.1rem" }}>
                          {describePolicy(plan).map((l) => (
                            <li key={l}>{l}</li>
                          ))}
                        </ul>
                        <div>
                          <button className="btn btn-primary" disabled={!p.available} onClick={() => setChosen(plan)}>
                            Subscribe
                          </button>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </section>

              {chosen ? <SubscribeModal detail={d} plan={chosen} onClose={() => setChosen(null)} /> : null}
            </>
          );
        }}
      </Boundary>
    </main>
  );
}
