package com.firstfood.identity;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * F-17: the local and test profiles ship throwaway database credentials ({@code firstfood}/{@code firstfood}). If the
 * {@code production} profile ends up running with that password (a leftover default, a copied env file), refuse to
 * start rather than serve real traffic against a guessable database. The JWT-secret and profile-combination checks
 * live in {@link JwtSecretPolicy}.
 */
@Component
public class LocalDefaultsGuard implements InitializingBean {

    static final String DEV_DB_PASSWORD = "firstfood";

    private final Environment environment;

    public LocalDefaultsGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        check(environment.acceptsProfiles(org.springframework.core.env.Profiles.of("production")),
                environment.getProperty("spring.datasource.password"));
    }

    static void check(boolean production, String datasourcePassword) {
        if (production && DEV_DB_PASSWORD.equals(datasourcePassword)) {
            throw new IllegalStateException(
                    "Refusing to start: the 'production' profile is using the development database password. "
                            + "Set DATABASE_PASSWORD to a real secret.");
        }
    }
}
