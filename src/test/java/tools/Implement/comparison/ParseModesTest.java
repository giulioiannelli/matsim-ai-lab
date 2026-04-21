package tools.Implement.comparison;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * LLMs empirically send the {@code modes} argument in several styles: a clean
 * CSV, a quoted CSV ({@code "walk,pt,bike"}), or a JSON array literal
 * ({@code ["walk","pt"]}). {@link CompareRoutesTool#parseModes(String)} must
 * normalise all three shapes so the router loop sees plain lowercase mode names.
 */
class ParseModesTest {

    @Test
    void stripsDoubleQuotesAroundTheWholeList() {
        assertEquals(List.of("walk", "pt", "bike"),
                CompareRoutesTool.parseModes("\"walk,pt,bike\""));
    }

    @Test
    void stripsDoubleQuotesAroundEachItem() {
        assertEquals(List.of("walk", "pt"),
                CompareRoutesTool.parseModes("\"walk\",\"pt\""));
    }

    @Test
    void acceptsJsonArrayLiteral() {
        assertEquals(List.of("walk", "pt", "bike"),
                CompareRoutesTool.parseModes("[\"walk\", \"pt\", \"bike\"]"));
    }

    @Test
    void acceptsSingleQuotedItems() {
        assertEquals(List.of("car", "pt"),
                CompareRoutesTool.parseModes("'car', 'pt'"));
    }
}
