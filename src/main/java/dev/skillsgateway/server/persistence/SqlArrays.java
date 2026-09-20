package dev.skillsgateway.server.persistence;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Reading and writing {@code TEXT[]} columns through {@code JdbcClient}.
 *
 * <p>Public rather than package-visible because callers live in {@code auth}, {@code webhook} and
 * {@code vetting} as well as here; there is one implementation of the escaping precisely so a
 * second, unescaped one never appears.
 *
 * <p>Writes go as an array literal bound to an explicit {@code ::text[]} cast rather than through
 * {@code Connection.createArrayOf}: {@code JdbcClient}'s fluent API never hands out the connection.
 */
public final class SqlArrays {

    private SqlArrays() {}

    /**
     * {@code {"a","b"}} — the array literal form, with every element quoted. Null yields null, so a
     * nullable column's NULL survives the round trip rather than becoming an empty array; the two
     * mean different things on every column that uses this.
     */
    public static String literal(List<String> values) {
        if (values == null) {
            return null;
        }
        StringBuilder literal = new StringBuilder("{");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                literal.append(',');
            }
            literal.append('"')
                    .append(values.get(index).replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"');
        }
        return literal.append('}').toString();
    }

    /** The column's elements, or null when the column is NULL — never an empty list for a NULL. */
    public static List<String> read(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        return array == null ? null : List.of((String[]) array.getArray());
    }

    /** The column's elements, with a NULL column read as an empty list. */
    public static List<String> readOrEmpty(ResultSet rs, String column) throws SQLException {
        List<String> values = read(rs, column);
        return values == null ? List.of() : values;
    }
}
