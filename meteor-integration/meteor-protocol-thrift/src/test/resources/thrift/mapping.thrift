struct Note {
    7: optional string text
    1: required i64 id
    3: optional i32 priority
    4: optional map<string, list<Note>> children
}

exception NoteFailure {
    1: i32 code
}

service NoteService {
    Note echo(1: Note value)
    i32 difference(7: i32 left, 2: i32 right)
    void fail(1: i32 code) throws (3: NoteFailure failure)
    oneway void notify(1: string text)
    i32 notificationCount()
}

struct OptionalPrimitive {
    1: optional i32 value
}

service OptionalPrimitiveService {
    void accept(1: OptionalPrimitive value)
}

union RequiredUnion {
    1: required i32 value
}

service RequiredUnionService {
    void accept(1: RequiredUnion value)
}

service UndeclaredFailureService {
    void fail() throws (1: NoteFailure failure)
}

struct RequiredText {
    1: required string text
}

service RequiredTextService {
    RequiredText echo(1: RequiredText value)
}
