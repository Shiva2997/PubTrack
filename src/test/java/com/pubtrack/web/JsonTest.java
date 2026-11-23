package com.pubtrack.web;

import com.pubtrack.model.Role;
import com.pubtrack.model.User;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonTest {

    @Test
    void writesPrimitivesAndNull() {
        assertEquals("null", Json.write(null));
        assertEquals("42", Json.write(42));
        assertEquals("true", Json.write(true));
        assertEquals("12.50", Json.write(new BigDecimal("12.50")));
    }

    @Test
    void escapesSpecialCharacters() {
        assertEquals("\"a\\\"b\\\\c\\nd\"", Json.write("a\"b\\c\nd"));
        assertEquals("\"\\u003c/script>\"", Json.write("</script>"));
        assertEquals("\"\\u0001\"", Json.write("\u0001"));
    }

    @Test
    void writesRecordsUsingComponentNames() {
        User u = new User(7, "Dr. Sarah Chen", "sarah@x.dev", Role.REVIEWER);
        assertEquals("{\"id\":7,\"fullName\":\"Dr. Sarah Chen\",\"email\":\"sarah@x.dev\",\"role\":\"REVIEWER\"}",
                Json.write(u));
    }

    @Test
    void writesCollectionsMapsAndDates() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("when", LocalDateTime.of(2026, 11, 24, 9, 30));
        map.put("items", List.of(1, 2));
        map.put("missing", null);
        assertEquals("{\"when\":\"2026-11-24T09:30\",\"items\":[1,2],\"missing\":null}", Json.write(map));
        assertEquals("[null,\"x\"]", Json.write(Arrays.asList(null, "x")));
    }
}
