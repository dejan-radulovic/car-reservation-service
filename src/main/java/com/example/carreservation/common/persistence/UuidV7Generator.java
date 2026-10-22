package com.example.carreservation.common.persistence;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import java.util.UUID;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.id.uuid.UuidValueGenerator;

/**
 * Time-ordered UUIDv7 (RFC 9562) identifiers for Hibernate 6.6, which only ships random and
 * version-1 styles. Used as {@code @UuidGenerator(algorithm = UuidV7Generator.class)}.
 *
 * <p>Hibernate instantiates this class reflectively, so it must stay public with a no-arg
 * constructor. The JUG generator is shared and thread-safe; within one millisecond it increments
 * the random part as a counter, so identifiers are monotonic per JVM.
 *
 * <p>On Hibernate 7 this class can be replaced by {@code @UuidGenerator(style = VERSION_7)}.
 */
public final class UuidV7Generator implements UuidValueGenerator {

    private static final TimeBasedEpochGenerator GENERATOR = Generators.timeBasedEpochGenerator();

    @Override
    public UUID generateUuid(SharedSessionContractImplementor session) {
        return GENERATOR.generate();
    }
}
