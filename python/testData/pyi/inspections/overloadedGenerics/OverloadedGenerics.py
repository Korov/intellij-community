from m1 import g, Gen

g(<warning descr="Expected type 'dict', got 'int' instead">Gen(10).get(10, 10)</warning>)
g(Gen(10).get<warning descr="Unexpected type(s):(int, LiteralString)Possible type(s):(int, int)(str, int)">(10, 'foo')</warning>)
g(Gen('foo').get<warning descr="Unexpected type(s):(int, int)Possible type(s):(int, LiteralString)(str, LiteralString)">(10, 10)</warning>)
g(<warning descr="Expected type 'dict', got 'LiteralString' instead">Gen('foo').get(10, 'foo')</warning>)
