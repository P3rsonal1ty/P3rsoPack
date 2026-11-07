package ru.p3rso.pack;

import ru.p3rso.pack.model.NoteBlockStates;
import ru.p3rso.pack.model.Shifts;
import ru.p3rso.pack.config.Suggest;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ShiftsTest {
    @Test
    void shiftsSumToRequestedOffset() {
        for (int px = -3000; px <= 3000; px++) {
            int sum = 0;
            for (char c : Shifts.of(px).toCharArray()) sum += Shifts.advanceOf(c);
            assertEquals(px, sum, "px=" + px);
        }
        assertEquals("", Shifts.of(0));
        assertEquals(1, Shifts.of(-1024).length());
    }

    @Test
    void noteBlockMappingIsBijective() {
        Set<String> keys = new HashSet<>();
        for (int v = 0; v <= NoteBlockStates.MAX_VARIATION; v++) {
            int i = NoteBlockStates.instrument(v), n = NoteBlockStates.note(v);
            assertEquals(v, NoteBlockStates.variation(i, n));
            assertTrue(keys.add(NoteBlockStates.variantKey(i, n, false)));
        }
        assertEquals(674, NoteBlockStates.MAX_VARIATION);
        assertEquals("instrument=harp,note=0,powered=false", NoteBlockStates.variantKey(0, 0, false));
    }

    @Test
    void suggestions() {
        assertEquals("lore", Suggest.closest("lroe", List.of("name", "lore", "material")));
        assertNull(Suggest.closest("zzzzzz", List.of("name", "lore")));
    }
}
