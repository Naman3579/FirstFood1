"use client";

import { useState } from "react";
import { Banner, Field, Modal, Spinner } from "@/components/ui";
import { useAction, useQuery } from "@/lib/hooks";
import { membershipApi } from "@/lib/membership-api";
import { describeQuantity, planApi } from "@/lib/plan-api";
import { subscriptionApi, type Subscription } from "@/lib/subscription-api";
import { formatMoney, todayInIndia } from "@/lib/format";

/** Start a subscription for a customer. Overlaps, capacity and "plan must be active" are enforced by the server. */
export function CreateSubscriptionModal({
  providerId,
  membershipId,
  onClose,
  onCreated,
}: {
  providerId: string;
  membershipId?: string;
  onClose: () => void;
  onCreated: (s: Subscription) => void;
}) {
  const customers = useQuery((t) => membershipApi.list(t, providerId, false), [providerId], { enabled: !membershipId });
  const plans = useQuery((t) => planApi.list(t, providerId, false), [providerId]);
  const [member, setMember] = useState(membershipId ?? "");
  const [planId, setPlanId] = useState("");
  const [start, setStart] = useState(todayInIndia());
  const { run, busy } = useAction();

  const planList = plans.data ?? [];
  const loading = plans.data === undefined || (!membershipId && customers.data === undefined);
  const selectedPlan = planList.find((p) => p.id === planId);

  return (
    <Modal title="New subscription" onClose={onClose}>
      {loading ? (
        <Spinner />
      ) : planList.length === 0 ? (
        <Banner tone="warn">Create an active plan first - there is nothing to subscribe to yet.</Banner>
      ) : (
        <>
          {!membershipId ? (
            <Field label="Customer">
              {(id) => (
                <select id={id} className="select" value={member} onChange={(e) => setMember(e.target.value)}>
                  <option value="">Choose a customer…</option>
                  {(customers.data ?? []).map((c) => (
                    <option key={c.membershipId} value={c.membershipId}>
                      {c.fullName} · {c.phone}
                    </option>
                  ))}
                </select>
              )}
            </Field>
          ) : null}
          <Field label="Plan">
            {(id) => (
              <select id={id} className="select" value={planId} onChange={(e) => setPlanId(e.target.value)}>
                <option value="">Choose a plan…</option>
                {planList.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name} · {describeQuantity(p)} · {formatMoney(p.price, p.currency)}
                  </option>
                ))}
              </select>
            )}
          </Field>
          <Field label="Start date" hint="Defaults to today. Past dates aren't allowed.">
            {(id) => <input id={id} type="date" className="input" value={start} min={todayInIndia()} onChange={(e) => setStart(e.target.value)} />}
          </Field>
          {selectedPlan ? <p className="small muted">{selectedPlan.description ?? ""}</p> : null}
        </>
      )}
      <div className="modal-actions">
        <button className="btn" onClick={onClose}>Cancel</button>
        <button
          className="btn btn-primary"
          disabled={busy || !member || !planId || !start}
          onClick={async () => {
            const created = await run((t) => subscriptionApi.create(t, providerId, { membershipId: member, planId, startDate: start }), { success: "Subscription started." });
            if (created) onCreated(created);
          }}
        >
          {busy ? <Spinner /> : null} Start subscription
        </button>
      </div>
    </Modal>
  );
}
