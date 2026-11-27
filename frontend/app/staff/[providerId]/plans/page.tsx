"use client";

import { useState } from "react";
import { Archive, CalendarDays, Pencil, Plus, Power, UtensilsCrossed } from "lucide-react";
import { Badge, Banner, Boundary, ConfirmDialog, Empty, Field, Modal, PageHeader, Spinner } from "@/components/ui";
import { PlanStatusBadge } from "@/components/domain/status";
import { useProvider } from "@/components/shell/ProviderShell";
import { useAction, useQuery } from "@/lib/hooks";
import { describePolicy, describeQuantity, planApi, shortTime, type Plan, type PolicyInput } from "@/lib/plan-api";
import { formatMoney } from "@/lib/format";

function PlanForm({ plan, busy, onSubmit, onCancel }: { plan?: Plan; busy: boolean; onSubmit: (v: { type: "DAY" | "MEAL"; body: Omit<Parameters<typeof planApi.create>[2], "consumptionType"> }) => void; onCancel: () => void }) {
  const editing = Boolean(plan);
  const [type, setType] = useState<"DAY" | "MEAL">(plan?.consumptionType ?? "DAY");
  const [name, setName] = useState(plan?.name ?? "");
  const [description, setDescription] = useState(plan?.description ?? "");
  const [quantity, setQuantity] = useState(String((plan?.consumptionType === "MEAL" ? plan.mealQuantity : plan?.durationDays) ?? (type === "DAY" ? 30 : 30)));
  const [price, setPrice] = useState(plan ? String(plan.price) : "");
  const [extension, setExtension] = useState(plan?.policy.extensionAllowed ?? false);
  const [minRun, setMinRun] = useState(String(plan?.policy.minConsecutiveAbsenceDays ?? 3));
  const [sameDay, setSameDay] = useState(plan?.policy.sameDayAbsenceAllowed ?? true);
  const [cutoff, setCutoff] = useState(shortTime(plan?.policy.absenceCutoffTime ?? null) || "");
  const [window, setWindow] = useState(plan?.policy.maxCalendarWindowDays?.toString() ?? "");
  const [errors, setErrors] = useState<Record<string, string>>({});

  const isDay = type === "DAY";

  function submit(e: React.FormEvent) {
    e.preventDefault();
    const next: Record<string, string> = {};
    const q = Number(quantity);
    const p = Number(price);
    if (!name.trim()) next.name = "Required";
    if (!Number.isInteger(q) || q < 1 || q > (isDay ? 366 : 1000)) next.quantity = isDay ? "1 to 366 days" : "1 to 1000 meals";
    if (!(p > 0) || !/^\d{1,8}(\.\d{1,2})?$/.test(price)) next.price = "Greater than 0, up to 2 decimals";
    if (isDay && extension && !(Number(minRun) >= 1)) next.minRun = "At least 1";
    if (window && isDay && extension && Number(window) < q) next.window = `Can't be shorter than ${q} days`;
    setErrors(next);
    if (Object.keys(next).length) return;

    const policy: PolicyInput = {
      extensionAllowed: isDay && extension,
      minConsecutiveAbsenceDays: isDay && extension ? Number(minRun) : undefined,
      sameDayAbsenceAllowed: sameDay,
      absenceCutoffTime: sameDay && cutoff ? cutoff : undefined,
      maxCalendarWindowDays: window && (isDay ? extension : true) ? Number(window) : undefined,
    };
    onSubmit({
      type,
      body: {
        name: name.trim(),
        description: description.trim() || undefined,
        durationDays: isDay ? q : undefined,
        mealQuantity: isDay ? undefined : q,
        price: p,
        policy,
      },
    });
  }

  return (
    <form className="stack" onSubmit={submit} noValidate>
      {!editing ? (
        <div className="field">
          <span className="label">Plan type</span>
          <div className="seg" role="group" aria-label="Plan type">
            <button type="button" aria-pressed={isDay} onClick={() => setType("DAY")}>By days</button>
            <button type="button" aria-pressed={!isDay} onClick={() => { setType("MEAL"); setExtension(false); }}>By meals</button>
          </div>
          <span className="hint">{isDay ? "A fixed number of days, e.g. 30-day lunch + dinner." : "A fixed count of meals, e.g. 60 meals to use when you like."}</span>
        </div>
      ) : (
        <Banner>Plan type can&apos;t be changed after creation. Existing subscribers keep the terms they signed up with.</Banner>
      )}
      <div className="form-grid">
        <Field label="Plan name" error={errors.name}>
          {(id) => <input id={id} className="input" maxLength={120} value={name} onChange={(e) => setName(e.target.value)} placeholder="Monthly Lunch + Dinner" autoFocus />}
        </Field>
        <Field label={isDay ? "Duration (days)" : "Number of meals"} error={errors.quantity}>
          {(id) => <input id={id} className="input num" inputMode="numeric" value={quantity} onChange={(e) => setQuantity(e.target.value.replace(/\D/g, ""))} />}
        </Field>
        <Field label="Price (₹)" error={errors.price}>
          {(id) => <input id={id} className="input num" inputMode="decimal" value={price} onChange={(e) => setPrice(e.target.value.replace(/[^\d.]/g, ""))} placeholder="3000" />}
        </Field>
      </div>
      <Field label="Description" hint="What's included. Optional.">
        {(id) => <textarea id={id} className="textarea" maxLength={500} value={description} onChange={(e) => setDescription(e.target.value)} />}
      </Field>

      <fieldset className="card stack" style={{ background: "var(--surface-2)", boxShadow: "none", border: 0 }}>
        <legend className="strong" style={{ padding: 0 }}>Rules for absences</legend>
        {isDay ? (
          <label className="check">
            <input type="checkbox" checked={extension} onChange={(e) => setExtension(e.target.checked)} />
            <span>
              <span className="strong">Extend the plan for long absences</span>
              <span className="hint" style={{ display: "block" }}>Days a customer misses in a long enough run are added to the end.</span>
            </span>
          </label>
        ) : null}
        {isDay && extension ? (
          <div className="form-grid">
            <Field label="Minimum days in a row" error={errors.minRun}>
              {(id) => <input id={id} className="input num" inputMode="numeric" value={minRun} onChange={(e) => setMinRun(e.target.value.replace(/\D/g, ""))} />}
            </Field>
            <Field label="Hard stop (days from start)" error={errors.window} hint="Optional. Extensions never go past this.">
              {(id) => <input id={id} className="input num" inputMode="numeric" value={window} onChange={(e) => setWindow(e.target.value.replace(/\D/g, ""))} placeholder="No cap" />}
            </Field>
          </div>
        ) : null}
        {!isDay ? (
          <Field label="Use within (days, optional)" hint="Meals expire this many days after the start.">
            {(id) => <input id={id} className="input num" inputMode="numeric" value={window} onChange={(e) => setWindow(e.target.value.replace(/\D/g, ""))} placeholder="No limit" />}
          </Field>
        ) : null}
        <label className="check">
          <input type="checkbox" checked={sameDay} onChange={(e) => { setSameDay(e.target.checked); if (!e.target.checked) setCutoff(""); }} />
          <span>
            <span className="strong">Allow same-day absences</span>
            <span className="hint" style={{ display: "block" }}>Customers can skip today&apos;s meal.</span>
          </span>
        </label>
        {sameDay ? (
          <div style={{ maxWidth: "12rem" }}>
            <Field label="Cutoff time" hint="Optional - e.g. 08:00">
              {(id) => <input id={id} type="time" className="input" value={cutoff} onChange={(e) => setCutoff(e.target.value)} />}
            </Field>
          </div>
        ) : null}
      </fieldset>

      <div className="modal-actions">
        <button type="button" className="btn" onClick={onCancel}>Cancel</button>
        <button className="btn btn-primary" disabled={busy}>{busy ? <Spinner /> : null}{editing ? "Save plan" : "Create plan"}</button>
      </div>
    </form>
  );
}

export default function PlansPage() {
  const { providerId, can, provider } = useProvider();
  const [showInactive, setShowInactive] = useState(false);
  const plans = useQuery((t) => planApi.list(t, providerId, showInactive), [providerId, showInactive]);
  const [editing, setEditing] = useState<Plan | "new" | null>(null);
  const [toggling, setToggling] = useState<Plan | null>(null);
  const { run, busy } = useAction();
  const manage = can("PLAN_MANAGE") && provider.status !== "CLOSED";

  return (
    <main className="page">
      <PageHeader
        title="Plans"
        sub="What customers can subscribe to. Changing a plan never alters existing subscriptions."
        actions={manage ? <button className="btn btn-primary" onClick={() => setEditing("new")}><Plus size={18} /> New plan</button> : undefined}
      />
      <label className="check" style={{ alignItems: "center" }}>
        <input type="checkbox" checked={showInactive} onChange={(e) => setShowInactive(e.target.checked)} />
        Show inactive plans
      </label>

      <Boundary query={plans} rows={3}>
        {(list) =>
          list.length === 0 ? (
            <div className="card">
              <Empty icon={<UtensilsCrossed size={26} />} title="No plans yet" action={manage ? <button className="btn btn-primary" onClick={() => setEditing("new")}><Plus size={18} /> Create your first plan</button> : undefined}>
                A plan is what you sell: a number of days or meals, a price and the absence rules.
              </Empty>
            </div>
          ) : (
            <div className="grid grid-3">
              {list.map((p) => (
                <article key={p.id} className="card stack" style={p.status === "INACTIVE" ? { opacity: 0.75 } : undefined}>
                  <div className="row between row-nowrap" style={{ alignItems: "flex-start" }}>
                    <div style={{ minWidth: 0 }}>
                      <h3 className="truncate">{p.name}</h3>
                      <div className="row small muted" style={{ gap: "0.3rem", marginTop: "0.25rem" }}>
                        <CalendarDays size={14} /> {describeQuantity(p)}
                      </div>
                    </div>
                    <PlanStatusBadge status={p.status} />
                  </div>
                  <div className="num" style={{ fontFamily: "var(--font-display)", fontSize: "1.75rem", fontWeight: 800 }}>
                    {formatMoney(p.price, p.currency)}
                  </div>
                  {p.description ? <p className="small muted">{p.description}</p> : null}
                  <ul className="small muted" style={{ margin: 0, paddingLeft: "1.1rem" }}>
                    {describePolicy(p).map((l) => <li key={l}>{l}</li>)}
                  </ul>
                  <div className="row" style={{ marginTop: "auto" }}>
                    <Badge tone="neutral">Policy v{p.policy.version}</Badge>
                    <span className="grow" />
                    {manage ? (
                      <>
                        <button className="btn btn-sm" onClick={() => setEditing(p)}><Pencil size={14} /> Edit</button>
                        <button className="btn btn-sm" onClick={() => setToggling(p)}>{p.status === "ACTIVE" ? <><Archive size={14} /> Deactivate</> : <><Power size={14} /> Activate</>}</button>
                      </>
                    ) : null}
                  </div>
                </article>
              ))}
            </div>
          )
        }
      </Boundary>

      {editing ? (
        <Modal title={editing === "new" ? "New plan" : `Edit ${editing.name}`} onClose={() => setEditing(null)} wide>
          <PlanForm
            plan={editing === "new" ? undefined : editing}
            busy={busy}
            onCancel={() => setEditing(null)}
            onSubmit={async ({ type, body }) => {
              const saved = editing === "new"
                ? await run((t) => planApi.create(t, providerId, { ...body, consumptionType: type }), { success: "Plan created." })
                : await run((t) => planApi.update(t, providerId, editing.id, body), { success: "Plan updated." });
              if (saved) {
                setEditing(null);
                await plans.reload();
              }
            }}
          />
        </Modal>
      ) : null}

      {toggling ? (
        <ConfirmDialog
          title={toggling.status === "ACTIVE" ? "Deactivate plan?" : "Activate plan?"}
          confirmLabel={toggling.status === "ACTIVE" ? "Deactivate" : "Activate"}
          danger={toggling.status === "ACTIVE"}
          busy={busy}
          onClose={() => setToggling(null)}
          onConfirm={async () => {
            const done = await run((t) => (toggling.status === "ACTIVE" ? planApi.deactivate(t, providerId, toggling.id) : planApi.activate(t, providerId, toggling.id)), {
              success: toggling.status === "ACTIVE" ? "Plan deactivated." : "Plan activated.",
            });
            if (done) {
              setToggling(null);
              await plans.reload();
            }
          }}
        >
          <p>
            {toggling.status === "ACTIVE"
              ? `"${toggling.name}" will no longer be available for new subscriptions. People already on it are not affected.`
              : `"${toggling.name}" will be available for new subscriptions again.`}
          </p>
        </ConfirmDialog>
      ) : null}
    </main>
  );
}
