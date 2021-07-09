namespace smithy.example

service Example {
    version: "1.0.0",
    operations: [GetFoo],
}

operation GetFoo {
    input: GetFooInput,
    output: GetFooOutput,
}

structure GetFooInput {
    union: MyUnion,
}
structure GetFooOutput {}

union MyUnion {
    value: Integer,
    stringA: String,
    stringB: String,
}
