// "Add parameters to constructor 'Foo'" "true"
// DISABLE-ERRORS
enum class Foo {
    A(1, 2<caret>),
    B(3),
    C(3, 4),
    D(),
    E
}
// FUS_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.AddFunctionParametersFix
// IGNORE_K2