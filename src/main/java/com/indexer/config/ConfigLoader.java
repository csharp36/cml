package com.indexer.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ConfigLoader {

    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{([^}]+)}");
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private final Function<String, String> envResolver;

    public ConfigLoader() {
        this(System::getenv);
    }

    public ConfigLoader(Function<String, String> envResolver) {
        this.envResolver = envResolver;
    }

    public IndexerConfig load(Path configPath) throws IOException {
        try (InputStream is = Files.newInputStream(configPath)) {
            return load(is);
        }
    }

    public IndexerConfig load(InputStream inputStream) throws IOException {
        JsonNode root = YAML_MAPPER.readTree(inputStream);
        JsonNode resolved = resolveEnvVars(root);
        return parseConfig(resolved);
    }

    // Recursively walk the JSON tree and replace ${VAR} patterns in text nodes.
    private JsonNode resolveEnvVars(JsonNode node) {
        if (node.isTextual()) {
            String text = node.textValue();
            String replaced = resolveString(text);
            return replaced.equals(text) ? node : new TextNode(replaced);
        } else if (node.isObject()) {
            ObjectNode obj = YAML_MAPPER.createObjectNode();
            node.fields().forEachRemaining(entry ->
                    obj.set(entry.getKey(), resolveEnvVars(entry.getValue())));
            return obj;
        } else if (node.isArray()) {
            ArrayNode arr = YAML_MAPPER.createArrayNode();
            node.elements().forEachRemaining(element -> arr.add(resolveEnvVars(element)));
            return arr;
        }
        return node;
    }

    private String resolveString(String text) {
        Matcher matcher = ENV_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String varName = matcher.group(1);
            String value = envResolver.apply(varName);
            if (value == null) value = "";
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private IndexerConfig parseConfig(JsonNode root) {
        IndexerConfig.ServerConfig server = parseServer(root.get("server"));
        IndexerConfig.DatabaseConfig database = parseDatabase(root.get("database"));
        List<IndexerConfig.RepositoryConfig> repositories = parseRepositories(root.get("repositories"));
        IndexerConfig.LanguagesConfig languages = parseLanguages(root.get("languages"));
        IndexerConfig.AdminConfig admin = parseAdmin(root.get("admin"));
        IndexerConfig.BranchConfig branches = parseBranches(root.get("branches"));

        return new IndexerConfig(server, database, repositories, languages, admin, branches);
    }

    private IndexerConfig.ServerConfig parseServer(JsonNode node) {
        if (node == null) return null;
        String cloneBaseDir = textOrNull(node, "cloneBaseDir");
        long maxFileSizeBytes = node.has("maxFileSizeBytes") ? node.get("maxFileSizeBytes").asLong(0) : 0;
        int indexWorkers = node.has("indexWorkers") ? node.get("indexWorkers").asInt(0) : 0;
        int httpPort = node.has("httpPort") ? node.get("httpPort").asInt(0) : 0;
        return new IndexerConfig.ServerConfig(cloneBaseDir, maxFileSizeBytes, indexWorkers, httpPort);
    }

    private IndexerConfig.DatabaseConfig parseDatabase(JsonNode node) {
        if (node == null) return null;
        String host = textOrNull(node, "host");
        int port = node.has("port") ? node.get("port").asInt(0) : 0;
        String name = textOrNull(node, "name");
        String username = textOrNull(node, "username");
        String password = textOrNull(node, "password");
        return new IndexerConfig.DatabaseConfig(host, port, name, username, password);
    }

    private List<IndexerConfig.RepositoryConfig> parseRepositories(JsonNode node) {
        if (node == null || !node.isArray()) return null;
        List<IndexerConfig.RepositoryConfig> repos = new ArrayList<>();
        for (JsonNode repoNode : node) {
            repos.add(parseRepository(repoNode));
        }
        return repos;
    }

    private IndexerConfig.RepositoryConfig parseRepository(JsonNode node) {
        String url = textOrNull(node, "url");
        String branch = textOrNull(node, "branch");
        IndexerConfig.AuthConfig auth = parseAuth(node.get("auth"));
        return new IndexerConfig.RepositoryConfig(url, branch, auth);
    }

    /**
     * The auth section has a "type" field plus arbitrary key-value pairs.
     * We extract "type" and put everything else into the "properties" map.
     */
    private IndexerConfig.AuthConfig parseAuth(JsonNode node) {
        if (node == null) return null;
        String type = textOrNull(node, "type");
        Map<String, String> properties = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (!"type".equals(entry.getKey()) && entry.getValue().isTextual()) {
                properties.put(entry.getKey(), entry.getValue().textValue());
            }
        }
        return new IndexerConfig.AuthConfig(type, properties);
    }

    private IndexerConfig.LanguagesConfig parseLanguages(JsonNode node) {
        if (node == null) return null;
        JsonNode extNode = node.get("customExtensions");
        Map<String, String> customExtensions = new HashMap<>();
        if (extNode != null && extNode.isObject()) {
            extNode.fields().forEachRemaining(entry -> {
                if (entry.getValue().isTextual()) {
                    customExtensions.put(entry.getKey(), entry.getValue().textValue());
                }
            });
        }
        return new IndexerConfig.LanguagesConfig(customExtensions);
    }

    private IndexerConfig.AdminConfig parseAdmin(JsonNode node) {
        if (node == null) return null;
        String token = textOrNull(node, "token");
        return new IndexerConfig.AdminConfig(token);
    }

    private IndexerConfig.BranchConfig parseBranches(JsonNode node) {
        if (node == null) return null;
        boolean autoIndex = !node.has("autoIndex") || node.get("autoIndex").asBoolean(true);
        int ttlDays = node.has("ttlDays") ? node.get("ttlDays").asInt(14) : 14;
        int cleanupIntervalHours = node.has("cleanupIntervalHours") ? node.get("cleanupIntervalHours").asInt(24) : 24;
        return new IndexerConfig.BranchConfig(autoIndex, ttlDays, cleanupIntervalHours);
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return (child != null && child.isTextual()) ? child.textValue() : null;
    }
}
