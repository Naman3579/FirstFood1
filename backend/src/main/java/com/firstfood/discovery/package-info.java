/**
 * discovery module: the customer portal's "find nearby messes and food providers, open a profile, browse its active
 * plans, subscribe" flow.
 *
 * Owns no tables. It reads providers and plans through their lookup services (public information only), ranks by
 * distance, and for "subscribe" orchestrates the membership and subscription application services in ONE
 * transaction, so a failed sale never leaves a dangling membership behind. Nothing here is authorized by a provider
 * role: every endpoint acts on the caller's own account.
 */
package com.firstfood.discovery;
