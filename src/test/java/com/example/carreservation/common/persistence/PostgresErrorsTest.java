package com.example.carreservation.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class PostgresErrorsTest {

    @Test
    void recognisesAnExclusionViolationAnywhereInTheCauseChain() {
        var root = new SQLException("conflicting key value violates exclusion constraint", "23P01");
        var translated = new DataIntegrityViolationException("could not execute statement",
                new RuntimeException("wrapper", root));

        assertThat(PostgresErrors.isExclusionViolation(translated)).isTrue();
    }

    @Test
    void doesNotMistakeOtherIntegrityViolations() {
        var checkViolation = new SQLException("violates check constraint", "23514");

        assertThat(PostgresErrors.isExclusionViolation(new RuntimeException(checkViolation))).isFalse();
    }

    @Test
    void skipsSqlExceptionsWithoutAStateAndFindsTheNextOne() {
        var withState = new SQLException("exclusion", "23P01");
        var withoutState = new SQLException("driver wrapper", null, withState);

        assertThat(PostgresErrors.isExclusionViolation(withoutState)).isTrue();
    }

    @Test
    void returnsFalseWithoutAnySqlException() {
        assertThat(PostgresErrors.isExclusionViolation(new IllegalStateException("no SQL here"))).isFalse();
        assertThat(PostgresErrors.isExclusionViolation(null)).isFalse();
    }
}
