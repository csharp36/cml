package com.indexer.config;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Registry for mapping file extensions / special filenames to language names
 * and for classifying languages into tiers (core, text, binary).
 */
public class LanguageRegistry {

    public static final Set<String> CORE_LANGUAGES = Set.of(
            "java", "python", "typescript", "javascript", "go", "c"
    );

    public static final Set<String> BINARY_EXTENSIONS = Set.of(
            ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".ico", ".svg",
            ".zip", ".tar", ".gz", ".bz2", ".xz", ".7z", ".rar",
            ".jar", ".war", ".ear", ".class",
            ".exe", ".dll", ".so", ".dylib", ".lib", ".a", ".o",
            ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
            ".mp3", ".mp4", ".avi", ".mov", ".mkv", ".wav", ".flac",
            ".ttf", ".otf", ".woff", ".woff2",
            ".bin", ".dat", ".db", ".sqlite"
    );

    public static final Map<String, String> DEFAULT_EXTENSIONS = Map.ofEntries(
            Map.entry(".java", "java"),
            Map.entry(".py", "python"),
            Map.entry(".ts", "typescript"),
            Map.entry(".tsx", "typescript"),
            Map.entry(".js", "javascript"),
            Map.entry(".jsx", "javascript"),
            Map.entry(".go", "go"),
            Map.entry(".kt", "kotlin"),
            Map.entry(".kts", "kotlin"),
            Map.entry(".rs", "rust"),
            Map.entry(".rb", "ruby"),
            Map.entry(".cs", "csharp"),
            Map.entry(".cpp", "cpp"),
            Map.entry(".cc", "cpp"),
            Map.entry(".cxx", "cpp"),
            Map.entry(".c", "c"),
            Map.entry(".h", "c"),
            Map.entry(".hpp", "cpp"),
            Map.entry(".swift", "swift"),
            Map.entry(".scala", "scala"),
            Map.entry(".php", "php"),
            Map.entry(".md", "markdown"),
            Map.entry(".yml", "yaml"),
            Map.entry(".yaml", "yaml"),
            Map.entry(".json", "json"),
            Map.entry(".xml", "xml"),
            Map.entry(".html", "html"),
            Map.entry(".htm", "html"),
            Map.entry(".css", "css"),
            Map.entry(".scss", "css"),
            Map.entry(".sql", "sql"),
            Map.entry(".sh", "shell"),
            Map.entry(".bash", "shell"),
            Map.entry(".zsh", "shell"),
            Map.entry(".toml", "toml"),
            Map.entry(".gradle", "groovy"),
            Map.entry(".groovy", "groovy"),
            Map.entry(".lua", "lua"),
            Map.entry(".r", "r"),
            Map.entry(".tf", "terraform"),
            Map.entry(".proto", "protobuf")
    );

    /** Special filenames that have no extension but map to a known language. */
    private static final Map<String, String> SPECIAL_FILENAMES = Map.of(
            "Makefile", "makefile",
            "makefile", "makefile",
            "GNUmakefile", "makefile",
            "Dockerfile", "dockerfile",
            "dockerfile", "dockerfile"
    );

    private final Map<String, String> effectiveExtensions;

    /**
     * @param customExtensions extra extension→language mappings (from user config).
     *                         These take priority over the built-in defaults.
     */
    public LanguageRegistry(Map<String, String> customExtensions) {
        Map<String, String> merged = new HashMap<>(DEFAULT_EXTENSIONS);
        if (customExtensions != null) {
            customExtensions.forEach((ext, lang) -> merged.put(
                    ext.startsWith(".") ? ext : "." + ext, lang));
        }
        this.effectiveExtensions = Map.copyOf(merged);
    }

    /**
     * Detect the language for the given filename.
     * <p>
     * Resolution order:
     * <ol>
     *   <li>Special filenames (Makefile, Dockerfile, …)</li>
     *   <li>Extension lookup in the merged (custom + default) map</li>
     *   <li>"plaintext" fallback</li>
     * </ol>
     */
    public String detectLanguage(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "plaintext";
        }

        // Strip any leading path components
        String name = filename.contains("/")
                ? filename.substring(filename.lastIndexOf('/') + 1)
                : filename;

        // 1. Special filenames
        String special = SPECIAL_FILENAMES.get(name);
        if (special != null) {
            return special;
        }

        // 2. Extension lookup
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex >= 0) {
            String ext = name.substring(dotIndex);
            String lang = effectiveExtensions.get(ext);
            if (lang != null) {
                return lang;
            }
        }

        return "plaintext";
    }

    /**
     * Returns {@code true} if {@code language} is one of the core languages
     * that receive full structural (AST) parsing.
     */
    public boolean isCoreLanguage(String language) {
        return CORE_LANGUAGES.contains(language);
    }

    /**
     * Returns {@code true} if {@code filename} has a binary file extension.
     */
    public boolean isBinary(String filename) {
        if (filename == null || filename.isEmpty()) {
            return false;
        }
        String name = filename.contains("/")
                ? filename.substring(filename.lastIndexOf('/') + 1)
                : filename;
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex < 0) {
            return false;
        }
        return BINARY_EXTENSIONS.contains(name.substring(dotIndex).toLowerCase());
    }
}
