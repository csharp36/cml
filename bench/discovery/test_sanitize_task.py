import sanitize_task as st


def test_strips_merge_pr_line_keeps_description():
    raw = ("Merge pull request #17028 from mdogan/node-shutdown-allow-mutations\n"
           "Allow operations while node is shutting down")
    assert st.sanitize(raw) == "Allow operations while node is shutting down"


def test_pure_merge_noise_becomes_empty():
    # "Merge remote-tracking branch '...' into ..." has no description -> drop
    assert st.sanitize("Merge remote-tracking branch 'devozerov/issues/17042' into issues/17042") == ""
    assert st.sanitize("Merge branch 'master' into issues/17045") == ""


def test_strips_inline_pr_number_token():
    raw = ("added executor creation time metric (#16775)\n"
           "* exposed executor creation time metric")
    out = st.sanitize(raw)
    assert "#16775" not in out and "16775" not in out
    assert "added executor creation time metric" in out
    assert "exposed executor creation time metric" in out


def test_strips_dotted_fqn_but_keeps_bare_classname():
    # bare ClassName is legit task content; only fully-qualified dotted names leak file locations
    raw = "Removed ChannelInitializerProvider"
    assert st.sanitize(raw) == "Removed ChannelInitializerProvider"
    assert "<a class>" in st.sanitize("Refactor com.hazelcast.internal.FooBar usage")


def test_strips_explicit_source_path():
    raw = "Fix hazelcast/src/main/java/com/x/Foo.java handling"
    out = st.sanitize(raw)
    assert ".java" not in out
