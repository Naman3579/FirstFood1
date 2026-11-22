package com.firstfood.common.config;

/**
 * Which URL space belongs to which portal door (audit F-12 / F-14). Every {@code /api/**} route must fall in exactly
 * one of the three lists; {@code PortalRouteCoverageTest} fails when a new route is in none of them, so a route cannot
 * silently miss its gate again (F-14: {@code PUT /api/v1/me/person/address} once did).
 *
 * <p>These are Spring {@code PathPattern}s. A portal is a door, not a security boundary (see {@code Portal}); the gate
 * only keeps the two UIs' APIs apart.
 */
public final class PortalPaths {

    /** The staff API and the original un-prefixed provider routes, which stay available. */
    public static final String[] STAFF = {"/api/v1/staff/**", "/api/v1/providers/**"};

    /**
     * The customer portal's own API: provider discovery, self-service subscribe, and the customer's own subscriptions,
     * profile (person), memberships and invitations. Every route under {@code /api/v1/me/person},
     * {@code /memberships} and {@code /invitations}, including sub-paths such as {@code /person/address}.
     */
    public static final String[] CUSTOMER = {
        "/api/v1/discover/**",
        "/api/v1/subscriptions/**",
        "/api/v1/me/person", "/api/v1/me/person/**",
        "/api/v1/me/memberships", "/api/v1/me/memberships/**",
        "/api/v1/me/invitations", "/api/v1/me/invitations/**"
    };

    /**
     * Deliberately in neither portal: the public version endpoint, sign-in itself, and the account-level self-service
     * both portals share (profile, email, phone change).
     */
    public static final String[] SHARED = {
        "/api/v1/version",
        "/api/v1/auth/**",
        "/api/v1/me",
        "/api/v1/me/email", "/api/v1/me/email/**",
        "/api/v1/me/phone", "/api/v1/me/phone/**"
    };

    private PortalPaths() {
    }
}
