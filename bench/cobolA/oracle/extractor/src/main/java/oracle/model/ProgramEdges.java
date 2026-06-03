package oracle.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Locked data contract for one COBOL program's outbound edges.
 *
 * <p>Field <em>order</em> mirrors the B1..B4 task pipeline. Jackson serialises with the
 * snake_case names below; B2/B3/B4 read those exact JSON keys.
 *
 * <p>B1 (this task) fills everything EXCEPT:
 * <ul>
 *   <li>{@code resolved_dynamic_calls}, {@code resolved_dynamic_xctl_link},
 *       {@code unresolved_dynamic_count} — B2 (dynamic-call resolution)</li>
 *   <li>{@code copybooks}, {@code cics_txn_entry} — B3 / later</li>
 * </ul>
 * Those are left empty/zero here.
 */
public final class ProgramEdges {

    @JsonProperty("program_id")
    public String programId;

    @JsonProperty("static_calls")
    public List<String> staticCalls = new ArrayList<>();

    @JsonProperty("dynamic_call_idents")
    public List<String> dynamicCallIdents = new ArrayList<>();

    @JsonProperty("resolved_dynamic_calls")
    public List<String> resolvedDynamicCalls = new ArrayList<>();

    @JsonProperty("static_xctl_link")
    public List<String> staticXctlLink = new ArrayList<>();

    @JsonProperty("dynamic_xctl_idents")
    public List<String> dynamicXctlIdents = new ArrayList<>();

    @JsonProperty("resolved_dynamic_xctl_link")
    public List<String> resolvedDynamicXctlLink = new ArrayList<>();

    @JsonProperty("unresolved_dynamic_count")
    public int unresolvedDynamicCount;

    @JsonProperty("copybooks")
    public List<String> copybooks = new ArrayList<>();

    @JsonProperty("files_read")
    public List<String> filesRead = new ArrayList<>();

    @JsonProperty("files_written")
    public List<String> filesWritten = new ArrayList<>();

    @JsonProperty("db2_tables")
    public List<String> db2Tables = new ArrayList<>();

    @JsonProperty("cics_txn_entry")
    public List<String> cicsTxnEntry = new ArrayList<>();
}
