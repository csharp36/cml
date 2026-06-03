from build_oracle import parse_javap_decl, transitive_closure

def test_parse_javap_decl_extracts_super_and_interfaces():
    line = "public class com.example.UserRepo extends com.example.AbstractRepo implements com.example.Repository, java.io.Serializable {"
    fqn, supers = parse_javap_decl(line)
    assert fqn == "com.example.UserRepo"
    assert set(supers) == {"com.example.AbstractRepo", "com.example.Repository", "java.io.Serializable"}

def test_transitive_closure_walks_extends_and_implements():
    graph = {
        "AbstractRepo": ["Repository"],
        "UserRepo": ["AbstractRepo"],
        "AdminRepo": ["UserRepo"],
        "Unrelated": ["OtherThing"],
    }
    assert transitive_closure(graph, "Repository") == {"AbstractRepo", "UserRepo", "AdminRepo"}
