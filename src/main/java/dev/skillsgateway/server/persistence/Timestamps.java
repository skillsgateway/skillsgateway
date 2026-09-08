package dev.skillsgateway.server.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * Reading a {@code timestamptz} column as an {@link Instant}, for the reads that are still mapped
 * by hand.
 *
 * <p>Most reads no longer need this: a record component of type {@code Instant} is filled from a
 * {@code timestamptz} by {@code DataClassRowMapper} with no help, which
 * {@code DataClassRowMapperTests} pins. What is left is the composite reads and the ledger
 * aggregates, whose shapes have no matching record — and this used to live on
 * {@code MarketplaceRepository}, which no longer maps anything by hand at all.
 */
final class Timestamps {

    private Timestamps() {}

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
