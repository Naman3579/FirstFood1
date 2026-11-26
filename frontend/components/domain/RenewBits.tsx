"use client";

import { useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { RefreshCw } from "lucide-react";
import { Banner, Field, Modal, Spinner } from "@/components/ui";
import { useToast } from "@/components/ui/toast";
import { attachIntendedPayment, hasErrors, intentErrors, newIntent, PaymentChoice, type PaymentIntent } from "@/components/domain/PaymentBits";
import { discoveryApi } from "@/lib/discovery-api";
import { useAction, useQuery } from "@/lib/hooks";
import { newIdempotencyKey } from "@/lib/payment-api";
import { subscriptionApi, type MySubscription } from "@/lib/subscription-api";
import { describeQuantity } from "@/lib/plan-api";
import { formatDate, formatMoney, todayInIndia } from "@/lib/format";

/**
 * Renew from the customer's own ended subscription. A renewal is a NEW sale at today's price and policy, so the
 * price is read from the provider's current plan listing, never from the old subscription. The subscription is
 * created first and the optional payment is attached to its id afterwards; if the payment step fails, the renewal
 * stands and the customer is sent on to add the payment from the new subscription's page.
 */
function RenewModal({ sub, onClose }: { sub: MySubscription; onClose: () => void }) {
  const router = useRouter();
  const toast = useToast();
  const { run, busy } = useAction();
  const detail = useQuery((t) => discoveryApi.get(t, sub.providerId), [sub.providerId]);
  // The plan as it is offered today. Missing when the provider no longer lists it (the server then refuses the renewal).
  const plan = detail.data?.plans.find((p) => p.id === sub.planId);
  const [start, setStart] = useState(todayInIndia());
  const [intent, setIntent] = useState<PaymentIntent>(() => newIntent(0));
  const [showErrors, setShowErrors] = useState(false);
  const paymentKey = useRef(newIdempotencyKey());

  const price = plan?.price ?? 0;
  const currency = plan?.currency ?? sub.terms.currency;
  // Without a known price there is nothing to validate an amount against, so only "pay later" is offered here.
  const canRecordPayment = plan !== undefined;
  const errors = canRecordPayment ? intentErrors(intent, price, currency) : {};

  async function confirm() {
    setShowErrors(true);
    if (hasErrors(errors)) return;
    const renewed = await run((t) => subscriptionApi.renewMine(t, sub.id, { startDate: start }));
    if (!renewed) return;
    const paid = canRecordPayment ? await attachIntendedPayment(run, renewed.id, intent, paymentKey.current) : true;
    if (!paid) toast.info("Your plan is renewed, but your payment wasn't saved. Add it from the subscription page.");
    router.push(`/subscriptions/${renewed.id}/renewed`);
  }

  return (
    <Modal title={`Renew ${sub.terms.planName}`} onClose={onClose}>
      <p className="muted">
        {sub.providerName}
        {plan ? ` · ${describeQuantity(plan)} · ` : null}
        {plan ? <span className="strong">{formatMoney(plan.price, plan.currency)}</span> : null}
      </p>
      {detail.data && !plan ? (
        <Banner tone="warn">This plan isn&apos;t listed any more, so the price can&apos;t be shown here. The provider may still renew it for you.</Banner>
      ) : null}
      <Field label="New plan starts" hint="Defaults to today. A renewal must start after your last plan ended.">
        {(id) => <input id={id} type="date" className="input" value={start} min={todayInIndia()} onChange={(e) => setStart(e.target.value)} />}
      </Field>
      <p className="small faint">Renewing is a new purchase: you get today&apos;s price and terms, which may differ from your last plan.</p>
      {canRecordPayment ? (
        <PaymentChoice
          intent={intent}
          onChange={setIntent}
          errors={showErrors ? errors : {}}
          price={price}
          currency={currency}
        />
      ) : null}
      <div className="modal-actions">
        <button className="btn" onClick={onClose} disabled={busy}>Cancel</button>
        <button className="btn btn-primary" onClick={() => void confirm()} disabled={busy || !start || detail.data === undefined}>
          {busy ? <Spinner /> : <RefreshCw size={16} />} Confirm renewal
        </button>
      </div>
    </Modal>
  );
}

/** Shown on an ended, not-yet-renewed subscription. The server decides `renewable`; dates are never re-derived here. */
export function RenewCard({ sub }: { sub: MySubscription }) {
  const [open, setOpen] = useState(false);
  if (!sub.renewable) return null;
  return (
    <section className="card row between" style={{ gap: "1rem" }}>
      <div>
        <div className="strong">Renew this plan</div>
        <div className="small muted">
          {sub.effectiveExpiryDate ? `Ended ${formatDate(sub.effectiveExpiryDate)}. ` : ""}Renew to keep your meals going.
        </div>
      </div>
      <button className="btn btn-primary btn-sm" onClick={() => setOpen(true)}>
        <RefreshCw size={16} /> Renew
      </button>
      {open ? <RenewModal sub={sub} onClose={() => setOpen(false)} /> : null}
    </section>
  );
}
