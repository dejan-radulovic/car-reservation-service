package com.example.carreservation.common.persistence;

import java.sql.SQLException;

/**
 * Recognises PostgreSQL errors by SQLSTATE.
 *
 * <p>Hibernate 6.6 cannot extract constraint names for non-numeric SQLSTATEs such as {@code 23P01},
 * so the SQLSTATE of the underlying {@link SQLException} is the reliable signal.
 */
public final class PostgresErrors {

    /** {@code exclusion_violation}: a row conflicts with an EXCLUDE constraint. */
    public static final String EXCLUSION_VIOLATION = "23P01";

    private PostgresErrors() {
    }

    public static boolean isExclusionViolation(Throwable throwable) {
        return EXCLUSION_VIOLATION.equals(sqlState(throwable));
    }

    static String sqlState(Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            if (t instanceof SQLException sqlException && sqlException.getSQLState() != null) {
                return sqlException.getSQLState();
            }
        }
        return null;
    }
}
