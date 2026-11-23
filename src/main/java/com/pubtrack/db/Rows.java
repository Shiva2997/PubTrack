package com.pubtrack.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small JDBC helpers shared by the repositories. */
public final class Rows {

    private Rows() {
    }

    /** Binds parameters in order (index 1, 2, 3 ...). Null values are bound as SQL NULL. */
    public static void bind(PreparedStatement ps, List<?> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
    }

    public static LocalDateTime dateTime(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toLocalDateTime();
    }

    public static <E extends Enum<E>> E enumOrNull(ResultSet rs, String column, Class<E> type) throws SQLException {
        String value = rs.getString(column);
        return value == null ? null : Enum.valueOf(type, value);
    }

    /** Converts any result set to a list of column-label → value maps (used for report queries). */
    public static List<Map<String, Object>> toMaps(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                Object value = rs.getObject(i);
                if (value instanceof Timestamp ts) {
                    value = ts.toLocalDateTime();
                }
                row.put(meta.getColumnLabel(i), value);
            }
            rows.add(row);
        }
        return rows;
    }
}
