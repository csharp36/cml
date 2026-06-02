You are fixing a defect in the Hazelcast codebase, a large Java project. Your working
directory is the repository root.

## Requirement

A `MapInterceptor` must not be able to corrupt data stored in a map by mutating the
value object passed to its interceptor methods. Any mutation an interceptor makes to its
input value must NOT affect the value that is stored in the map.

## Definition of done

Failing tests in the repository already specify this behavior. Make them pass without
modifying any test file. Verify with exactly this command:

    mvn -pl hazelcast -am -Dtest=InterceptorTest,EntryProcessorInterceptorTest,OffloadableEntryProcessorInterceptorTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test

When those tests pass, you are done. Do not edit files under `src/test/`.
