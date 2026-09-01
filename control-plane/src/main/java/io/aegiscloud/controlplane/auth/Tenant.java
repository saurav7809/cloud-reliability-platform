package io.aegiscloud.controlplane.auth;

import java.util.UUID;

/**
 * The organisation whose data the current request may see.
 *
 * <p>Multi-tenancy is enforced in the queries themselves rather than by filtering
 * results after the fact. Filtering afterwards means the wrong rows were already
 * fetched, already counted in an aggregate, and one forgotten filter away from being
 * returned; scoping in SQL means the rows never leave the database.
 *
 * <p>Read paths that serve tenant data call {@link #currentOrgId()} and pass the
 * result into the query. Engines running on a timer have no request and therefore no
 * caller — they operate across organisations by design, which is why they use the
 * unscoped queries and why those are named to say so.
 */
public final class Tenant {

    private Tenant() {
    }

    /**
     * The caller's organisation.
     *
     * @throws io.aegiscloud.controlplane.web.ApiException 401 when there is no
     *         authenticated caller. Returning a default would be the single most
     *         dangerous line in the codebase: every tenant-scoped query would
     *         silently read somebody's data.
     */
    public static UUID currentOrgId() {
        return CurrentUser.get().orgId();
    }
}
