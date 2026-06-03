package oracle;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CopyScannerTest {

    @Test
    void scansCopyNamesAndIgnoresComments() {
        String src = String.join("\n",
                "000100     COPY COCOM01Y.",
                "000200* COPY NOPE",
                "000300     COPY  COMEN01  REPLACING ==X== BY ==Y==.");
        assertEquals(Set.of("COCOM01Y", "COMEN01"), CopyScanner.scan(src));
    }
}
