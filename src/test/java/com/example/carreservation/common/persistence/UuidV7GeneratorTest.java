package com.example.carreservation.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidV7GeneratorTest {

    private final UuidV7Generator generator = new UuidV7Generator();

    @Test
    void generatesRfc9562Version7Uuids() {
        UUID uuid = generator.generateUuid(null);

        assertThat(uuid.version()).isEqualTo(7);
        assertThat(uuid.variant()).isEqualTo(2);
    }

    @Test
    void generatesStrictlyIncreasingUuidsWithinOneJvm() {
        List<String> generated = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            // the canonical hex form sorts in unsigned byte order, i.e. the order PostgreSQL uses
            generated.add(generator.generateUuid(null).toString());
        }

        assertThat(generated).isSorted().doesNotHaveDuplicates();
    }
}
