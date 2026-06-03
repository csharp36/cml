package oracle;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans RAW fixed-format COBOL source for {@code COPY <name>} statements.
 *
 * <p>ProLeap's preprocessor erases {@code COPY} directives (it expands them inline), so the
 * copybook dependency edges cannot be recovered from the ASG — they must be read off the raw
 * source text before preprocessing. This scanner applies the Phase-0 fixed-format rule:
 * <ul>
 *   <li>Lines shorter than 7 columns contribute nothing.</li>
 *   <li>A comment line — column 7 (0-based index 6) is {@code *} or {@code /} — is skipped.</li>
 *   <li>The code area is columns 8..72 ({@code substring(7, min(72, len))}); within it we match
 *       {@code (?i)\bCOPY\s+([A-Z0-9][A-Z0-9-]*)}.</li>
 * </ul>
 * Names are upper-cased and de-duplicated (insertion order preserved).
 */
public final class CopyScanner {

    private static final Pattern COPY = Pattern.compile("(?i)\\bCOPY\\s+([A-Z0-9][A-Z0-9-]*)");

    private CopyScanner() {
    }

    public static Set<String> scan(String source) {
        Set<String> names = new LinkedHashSet<>();
        if (source == null) {
            return names;
        }
        for (String line : source.split("\n", -1)) {
            // Strip a trailing CR for CRLF sources so column math stays correct.
            if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') {
                line = line.substring(0, line.length() - 1);
            }
            if (line.length() < 7) {
                continue; // no code area
            }
            char indicator = line.charAt(6);
            if (indicator == '*' || indicator == '/') {
                continue; // comment line
            }
            String codeArea = line.substring(7, Math.min(72, line.length()));
            Matcher m = COPY.matcher(codeArea);
            while (m.find()) {
                names.add(m.group(1).toUpperCase());
            }
        }
        return names;
    }
}
