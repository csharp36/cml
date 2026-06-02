import extract_truth as et

def test_is_source_file():
    assert et.is_source_file("hazelcast/src/main/java/com/hazelcast/map/Foo.java")
    assert not et.is_source_file("hazelcast/src/test/java/com/hazelcast/map/FooTest.java")
    assert not et.is_source_file("hazelcast/src/main/java/com/hazelcast/map/FooIT.java")
    assert not et.is_source_file("docs/readme.md")
    assert not et.is_source_file("hazelcast/src/main/resources/x.xml")

SAMPLE_DIFF = '''diff --git a/hazelcast/src/main/java/com/hazelcast/map/impl/operation/BasePutOperation.java b/hazelcast/src/main/java/com/hazelcast/map/impl/operation/BasePutOperation.java
index 1111..2222 100644
--- a/hazelcast/src/main/java/com/hazelcast/map/impl/operation/BasePutOperation.java
+++ b/hazelcast/src/main/java/com/hazelcast/map/impl/operation/BasePutOperation.java
@@ -40,7 +40,7 @@ public void afterRunInternal() {
-        mapServiceContext.interceptAfterPut(mapContainer.getInterceptorRegistry(), dataValue);
+        mapServiceContext.interceptAfterPut(mapContainer.getInterceptorRegistry(), value);
diff --git a/hazelcast/src/test/java/com/hazelcast/map/InterceptorTest.java b/hazelcast/src/test/java/com/hazelcast/map/InterceptorTest.java
index 3333..4444 100644
--- a/hazelcast/src/test/java/com/hazelcast/map/InterceptorTest.java
+++ b/hazelcast/src/test/java/com/hazelcast/map/InterceptorTest.java
@@ -10,0 +11,5 @@ public class InterceptorTest {
+    // new test
'''

def test_parse_diff_files_excludes_tests():
    files, symbols = et.parse_diff(SAMPLE_DIFF)
    assert files == {"hazelcast/src/main/java/com/hazelcast/map/impl/operation/BasePutOperation.java"}

def test_parse_diff_symbols_from_funcname():
    files, symbols = et.parse_diff(SAMPLE_DIFF)
    assert "BasePutOperation#afterRunInternal" in symbols

def test_parse_diff_classlevel_when_no_method():
    diff = ('+++ b/hazelcast/src/main/java/com/hazelcast/map/Foo.java\n'
            '@@ -1 +1 @@ \n+x\n')
    files, symbols = et.parse_diff(diff)
    assert symbols == {"Foo"}
