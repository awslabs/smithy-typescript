package software.amazon.smithy.typescript.codegen;

import org.junit.jupiter.api.Test;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;


public class TypeScriptTracingIdTest {
    @Test
    public void parsesTracingIdFromString() {
        TypeScriptTracingId methodTracingId = TypeScriptTracingId.fromString("foo.Bar#method");
        TypeScriptTracingId fieldTracingId = TypeScriptTracingId.fromString("foo.Bar$field");
        TypeScriptTracingId classTracingId = TypeScriptTracingId.fromString("foo.Bar");

        TypeScriptTracingId expectedMethodId = TypeScriptTracingId.builder()
                .appendToPackageName("foo.Bar")
                .methodName("method")
                .build();

        TypeScriptTracingId expectedFieldId = TypeScriptTracingId.builder()
                .appendToPackageName("foo.Bar")
                .fieldName("field")
                .build();

        TypeScriptTracingId expectedClassId = TypeScriptTracingId.builder()
                .appendToPackageName("foo.Bar")
                .build();

        assertThat(methodTracingId.getFieldName(), equalTo(expectedMethodId.getFieldName()));
        assertThat(methodTracingId.getMethodName(), equalTo(expectedMethodId.getMethodName()));
        assertThat(methodTracingId.getPackageName(), equalTo(expectedMethodId.getPackageName()));

        assertThat(fieldTracingId.getFieldName(), equalTo(expectedFieldId.getFieldName()));
        assertThat(fieldTracingId.getMethodName(), equalTo(expectedFieldId.getMethodName()));
        assertThat(fieldTracingId.getPackageName(), equalTo(expectedFieldId.getPackageName()));

        assertThat(classTracingId.getFieldName(), equalTo(expectedClassId.getFieldName()));
        assertThat(classTracingId.getMethodName(), equalTo(expectedClassId.getMethodName()));
        assertThat(classTracingId.getPackageName(), equalTo(expectedClassId.getPackageName()));
    }

    @Test
    public void convertsTracingIdToString() {
        TypeScriptTracingId methodId = TypeScriptTracingId.builder()
                .appendToPackageName("foo.Bar")
                .methodName("method")
                .build();

        TypeScriptTracingId fieldId = TypeScriptTracingId.builder()
                .appendToPackageName("foo.Bar")
                .fieldName("field")
                .build();

        TypeScriptTracingId classId = TypeScriptTracingId.builder()
                .appendToPackageName("foo.Bar")
                .build();

        assertThat(methodId.toString(), equalTo("foo.Bar#method"));
        assertThat(fieldId.toString(), equalTo("foo.Bar$field"));
        assertThat(classId.toString(), equalTo("foo.Bar"));
    }
}
