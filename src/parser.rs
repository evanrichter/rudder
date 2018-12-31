use nom::types::CompleteStr;
use nom::*;

// As recommended by nom, we use CompleteStr everywhere
// They are almost like str so this should not be a problem

// TODO identifier parsing
// TODO Error management
// TODO Store token location in file
// TODO Whole file parsing
// TODO add more types

// STRUCTURES
//

#[derive(Debug, PartialEq)]
pub struct PHeader {
    pub version: u32,
}

type PComment<'a> = Vec<CompleteStr<'a>>;

#[derive(Debug, PartialEq)]
pub struct PMetadata<'a> {
    pub key: CompleteStr<'a>,
    pub value: PValue<'a>,
}

#[derive(Debug, PartialEq)]
pub struct PParameter<'a> {
    pub name: CompleteStr<'a>,
    pub ptype: Option<PType>,
    pub default: Option<PValue<'a>>,
}

#[derive(Debug, PartialEq)]
pub enum PType {
    TString,
    TInteger,
    TStruct,
    TList,
}

#[derive(Debug, PartialEq)]
pub struct PSet<'a> {
    pub name: CompleteStr<'a>,
    pub items: Vec<CompleteStr<'a>>,
}

#[derive(Debug, PartialEq)]
pub struct PSetMapping<'a> {
    pub from: CompleteStr<'a>,
    pub to: CompleteStr<'a>,
    pub mapping: Vec<(CompleteStr<'a>, CompleteStr<'a>)>,
}

#[derive(Debug, PartialEq)]
pub enum PSetExpression<'a> {
    //      variable         set                      value
    Compare(CompleteStr<'a>, Option<CompleteStr<'a>>, CompleteStr<'a>),
    //       set                      value
    Classify(Option<CompleteStr<'a>>, CompleteStr<'a>),
    And(Box<PSetExpression<'a>>, Box<PSetExpression<'a>>),
    Or(Box<PSetExpression<'a>>, Box<PSetExpression<'a>>),
    Not(Box<PSetExpression<'a>>),
}

#[derive(Debug, PartialEq)]
pub struct PObjectDef<'a> {
    pub name: CompleteStr<'a>,
    pub parameters: Vec<PParameter<'a>>,
}

#[derive(Debug, PartialEq)]
pub enum PValue<'a> {
    String(String),
    XX(CompleteStr<'a>),
    //VInteger(u64),
    //...
}

#[derive(Debug, PartialEq)]
pub struct PObjectRef<'a> {
    pub name: CompleteStr<'a>,
    pub parameters: Vec<PValue<'a>>,
}

#[derive(Debug, PartialEq)]
pub enum PCallMode {
    Enforce,
    Condition,
    Check,
    CheckNot,
}

#[derive(Debug, PartialEq)]
pub enum PStatement<'a> {
    Comment(PComment<'a>),
    //        outcome                  mode       object          state name       parameters
    StateCall(
        Option<CompleteStr<'a>>,
        PCallMode,
        PObjectRef<'a>,
        CompleteStr<'a>,
        Vec<PValue<'a>>,
    ),
    //   list of condition          then
    Case(Vec<(PSetExpression<'a>, Box<PStatement<'a>>)>),
    // condition          then
    If(PSetExpression<'a>, Box<PStatement<'a>>),
    // Stop engine
    Fail(CompleteStr<'a>),
    // Inform the user of something
    Log(CompleteStr<'a>),
    // Do nothing
    Noop,
    // TODO condition instance, object instance, variable definition
}

#[derive(Debug, PartialEq)]
pub struct PStateDef<'a> {
    pub name: CompleteStr<'a>,
    pub object_name: CompleteStr<'a>,
    pub parameters: Vec<PParameter<'a>>,
    pub statements: Vec<PStatement<'a>>,
}

#[derive(Debug, PartialEq)]
pub enum PDeclaration<'a> {
    Comment(PComment<'a>),
    Metadata(PMetadata<'a>),
    Object(PObjectDef<'a>),
    State(PStateDef<'a>),
    Set(PSet<'a>),
    Mapping(PSetMapping<'a>),
}

#[derive(Debug, PartialEq)]
pub struct PCode<'a> {
    header: PHeader,
    code: Vec<PDeclaration<'a>>,
}

#[derive(Debug, PartialEq)]
pub enum Error {
    UnterminatedString,
}

// PARSERS adapter for str instead of byte{]
//

// eat char separators instead of u8
named!(space_s<CompleteStr,CompleteStr>, eat_separator!(&" \t\r\n"[..]));

// ws! for chars instead of u8
// TODO add a strip comment
macro_rules! sp (
  ($i:expr, $($args:tt)*) => (
    {
      sep!($i, space_s, $($args)*)
    }
  )
);

// PARSERS
//

named!(pub header<CompleteStr,PHeader>,
  do_parse!(
    opt!(preceded!(tag!("#!/"),take_until_and_consume!("\n"))) >>
    // strict parser so that this does not diverge and anything can read the line
    tag!("@format=") >>
    version: map_res!(take_until!("\n"), |s:CompleteStr| s.parse::<u32>()) >>
    tag!("\n") >>
    (PHeader { version })
  )
);


//    // Convert to IResult<&[u8], &[u8], ErrorStr>
//    impl From<u32> for PHeader {
//      fn from(i: u32) -> Self {
//        PHeader {version:1}
//      }
//    }
// string
named!(escaped_string<CompleteStr,String>,
//       fix_error!(PHeader,
    delimited!(
        tag!("\""), 
        escaped_transform!(
            take_until_either1!("\\\""),
            '\\',
            alt!(
                tag!("\\") |
                tag!("\"") |
                tag!("n") => { |_| CompleteStr("\n") } |
                tag!("r") => { |_| CompleteStr("\r") } |
                tag!("t") => { |_| CompleteStr("\t") }
            )
        ),
        add_return_error!(ErrorKind::Custom(Error::UnterminatedString as u32),tag!("\""))
    )
);

named!(unescaped_string<CompleteStr,String>,
    delimited!(
        // TODO string containing """
        tag!("\"\"\""),
        map!(take_until!("\"\"\""), { |x:CompleteStr| x.to_string() }),
        return_error!(ErrorKind::Custom(Error::UnterminatedString as u32),tag!("\"\"\""))
    )
);

// Value
//
named!(typed_value<CompleteStr,PValue>,
    // TODO other types
    alt!(
        unescaped_string  => { |x| PValue::String(x) }
      | escaped_string    => { |x| PValue::String(x) }
    )
);

// Sets
named!(set<CompleteStr,PSet>,
    sp!(do_parse!(
        tag!("set") >>
        name: alphanumeric >>
        tag!("{") >>
        items: sp!(separated_list!(tag!(","), alphanumeric)) >>
        tag!("}") >>
        (PSet {name, items})
    ))
);

named!(set_mapping<CompleteStr,PSetMapping>,
    sp!(do_parse!(
        tag!("set") >>
        from: alphanumeric >>
        tag!("->") >>
        to: alphanumeric >>
        tag!("{") >>
        mapping: sp!(separated_list!(
                tag!(","),
                separated_pair!(
                    alt!(alphanumeric|tag!("*")),
                    tag!("->"),
                    alphanumeric)
            )) >>
        tag!("}") >>
        (PSetMapping {from, to, mapping})
    ))
);

named!(set_expression<CompleteStr,PSetExpression>,
    alt!(set_or_expression
       | set_and_expression
       | set_not_expression
       | set_atom
    )
);

named!(set_atom<CompleteStr,PSetExpression>,
    sp!(alt!(
        delimited!(tag!("("),set_expression,tag!(")"))
      | do_parse!(
            var: alphanumeric >>
            tag!("==") >>
            set: opt!(terminated!(alphanumeric,tag!("/"))) >>
            value: alphanumeric >>
            (PSetExpression::Compare(var,set,value))
        )
      | do_parse!(
            set: opt!(terminated!(alphanumeric,tag!("/"))) >>
            value: alphanumeric >>
            (PSetExpression::Classify(set,value))
        )
    ))
);

named!(set_or_expression<CompleteStr,PSetExpression>,
    sp!(do_parse!(
        left: alt!(set_and_expression | set_not_expression | set_atom) >>
        tag!("||") >>
        right: alt!(set_or_expression | set_and_expression | set_not_expression | set_atom) >>
        (PSetExpression::Or(Box::new(left),Box::new(right)))
    ))
);

named!(set_and_expression<CompleteStr,PSetExpression>,
    sp!(do_parse!(
        left: alt!(set_not_expression | set_atom) >>
        tag!("&&") >>
        right: alt!(set_and_expression | set_not_expression | set_atom) >>
        (PSetExpression::And(Box::new(left),Box::new(right)))
    ))
);

named!(set_not_expression<CompleteStr,PSetExpression>,
    sp!(do_parse!(
        tag!("!") >>
        right: set_atom >>
        (PSetExpression::Not(Box::new(right)))
    ))
);

// comments
named!(comment_line<CompleteStr,CompleteStr>,
  preceded!(tag!("##"),
            alt!(take_until_and_consume!("\n")
                |rest
            )
  )
);
named!(comment_block<CompleteStr,PComment>,
  many1!(comment_line)
);

// metadata
named!(metadata<CompleteStr,PMetadata>,
  sp!(do_parse!(char!('@') >>
    key: alphanumeric >>
    char!('=') >>
    value: typed_value >>
    (PMetadata {key, value})
  ))
);

// type
named!(typename<CompleteStr,PType>,
  alt!(
    tag!("string")      => { |_| PType::TString }  |
    tag!("int")         => { |_| PType::TInteger } |
    tag!("struct")      => { |_| PType::TStruct }  |
    tag!("list")        => { |_| PType::TList }
  )
);

// Parameters
named!(parameter<CompleteStr,PParameter>,
  sp!(do_parse!(
    ptype: opt!(sp!(terminated!(typename, char!(':')))) >>
    name: alphanumeric >>
    default: opt!(sp!(preceded!(tag!("="),typed_value))) >>
    (PParameter {ptype, name, default})
  ))
);

// ObjectDef
named!(object_def<CompleteStr,PObjectDef>,
  sp!(do_parse!(
    tag!("object") >>
    name: alphanumeric >>
    tag!("(") >>
    parameters: separated_list!(
        tag!(","),
        parameter) >>
    tag!(")") >>
    (PObjectDef {name, parameters})
  ))
);

// ObjectRef
named!(object_ref<CompleteStr,PObjectRef>,
  sp!(do_parse!(
    name: alphanumeric >>
    params: opt!(sp!(do_parse!(tag!("(") >>
        parameters: separated_list!(
            tag!(","),
            typed_value) >>
        tag!(")") >>
        (parameters) ))) >>
    (PObjectRef {name, parameters: params.unwrap_or(vec![])})
  ))
);

// statements
named!(statement<CompleteStr,PStatement>,
  alt!(
      // state call
      sp!(do_parse!(
          outcome: opt!(terminated!(alphanumeric,tag!("="))) >>
          mode: alt!(
                    tag!("?") => { |_| PCallMode::Condition } |
                    pair!(tag!("not"), tag!("!")) => { |_| PCallMode::CheckNot }     |
                    tag!("!") => { |_| PCallMode::Check }     |
                    value!(PCallMode::Enforce)
                ) >>
          object: object_ref >>
          tag!(".") >>
          state: alphanumeric >>
          tag!("(") >>
          parameters: separated_list!(
              tag!(","),
              typed_value) >>
          tag!(")") >>
          (PStatement::StateCall(outcome,mode,object,state,parameters))
      ))
    | comment_block => { |x| PStatement::Comment(x) }
      // case
    | sp!(do_parse!(
          tag!("case") >>
          tag!("{") >>
          cases: separated_list!(tag!(","),
                    do_parse!(
                        expr: set_expression >>
                        tag!("=>") >>
                        stmt: statement >>
                        ((expr,Box::new(stmt)))
                )) >>
          (PStatement::Case(cases))
      ))
      // if
    | sp!(do_parse!(
          tag!("if") >>
          expr: set_expression >>
          tag!("=>") >>
          stmt: statement >>
          (PStatement::If(expr,Box::new(stmt)))
      ))
      // Flow statements
    | tag!("fail!") => { |_| PStatement::Fail(CompleteStr("failed")) } // TODO proper message
    | tag!("log!")  => { |_| PStatement::Log(CompleteStr("failed")) } // TODO proper message
    | tag!("noop!") => { |_| PStatement::Noop }
  )
);

// state definition
named!(state<CompleteStr,PStateDef>,
  sp!(do_parse!(
      object_name: alphanumeric >>
      tag!("state") >>
      name: alphanumeric >>
      tag!("(") >>
      parameters: separated_list!(
         tag!(","),
         parameter) >>
     tag!(")") >>
     tag!("{") >>
     statements: many0!(statement) >>
     tag!("}") >>
     (PStateDef {name, object_name, parameters, statements })
  ))
);

// a file
named!(declaration<CompleteStr,PDeclaration>,
    sp!(alt_complete!(
          object_def    => { |x| PDeclaration::Object(x) }
        | metadata      => { |x| PDeclaration::Metadata(x) }
        | state         => { |x| PDeclaration::State(x) }
        | comment_block => { |x| PDeclaration::Comment(x) }
        | set           => { |x| PDeclaration::Set(x) }
        | set_mapping   => { |x| PDeclaration::Mapping(x) }
      ))
);

//pub fn parse<'a>(input: CompleteStr<'a>) -> IResult<CompleteStr<'a>, PCode<'a>>
//{
//   let header = try!(header(input));
//   if header.version != 0 {
//       return Err(Err::Failure);
//   }
//
//}
named!(pub code<CompleteStr,Vec<PDeclaration>>,
  do_parse!(
    x: many0!(declaration) >>
    eof!() >>
    (x)
  )
);

// TESTS
//

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_strings() {
        assert_eq!(
            escaped_string(CompleteStr("\"hello\\n\\\"Herman\\\"\n\"")),
            Ok((CompleteStr(""), "hello\n\"Herman\"\n".to_string()))
        );
        assert_eq!(
            escaped_string(CompleteStr("\"hello\\n\\\"Herman\\\"\n")),
            Ok((CompleteStr(""), "hello\n\"Herman\"\n".to_string()))
        );
        assert_eq!(
            unescaped_string(CompleteStr("\"\"\"hello\\n\"Herman\"\n\"\"\"")),
            Ok((CompleteStr(""), "hello\\n\"Herman\"\n".to_string()))
        );
    }

    #[test]
    fn test_set() {
        assert_eq!(
            set(CompleteStr("set abc { a, b, c }")),
            Ok((
                CompleteStr(""),
                PSet {
                    name: CompleteStr("abc"),
                    items: vec![CompleteStr("a"), CompleteStr("b"), CompleteStr("c")]
                }
            ))
        );
        assert_eq!(
            set_mapping(CompleteStr("set abc -> def { a -> d, b -> e, * -> f}")),
            Ok((
                CompleteStr(""),
                PSetMapping {
                    from: CompleteStr("abc"),
                    to: CompleteStr("def"),
                    mapping: vec![
                        (CompleteStr("a"), CompleteStr("d")),
                        (CompleteStr("b"), CompleteStr("e")),
                        (CompleteStr("*"), CompleteStr("f"))
                    ]
                }
            ))
        );
    }

    #[test]
    fn test_set_expression() {
        assert_eq!(
            set_expression(CompleteStr("a==b/c")),
            Ok((
                CompleteStr(""),
                PSetExpression::Compare(CompleteStr("a"), Some(CompleteStr("b")), CompleteStr("c"))
            ))
        );
        assert_eq!(
            set_expression(CompleteStr("a==bc")),
            Ok((
                CompleteStr(""),
                PSetExpression::Compare(CompleteStr("a"), None, CompleteStr("bc"))
            ))
        );
        assert_eq!(
            set_expression(CompleteStr("bc")),
            Ok((
                CompleteStr(""),
                PSetExpression::Classify(None, CompleteStr("bc"))
            ))
        );
        assert_eq!(
            set_expression(CompleteStr("(a == b/hello)")),
            Ok((
                CompleteStr(""),
                PSetExpression::Compare(
                    CompleteStr("a"),
                    Some(CompleteStr("b")),
                    CompleteStr("hello")
                )
            ))
        );
        assert_eq!(
            set_expression(CompleteStr("bc&&(a||b==hello/g)")),
            Ok((
                CompleteStr(""),
                PSetExpression::And(
                    Box::new(PSetExpression::Classify(None, CompleteStr("bc"))),
                    Box::new(PSetExpression::Or(
                        Box::new(PSetExpression::Classify(None, CompleteStr("a"))),
                        Box::new(PSetExpression::Compare(
                            CompleteStr("b"),
                            Some(CompleteStr("hello")),
                            CompleteStr("g")
                        ))
                    )),
                )
            ))
        );
        assert_eq!(
            set_expression(CompleteStr("! a == hello")),
            Ok((
                CompleteStr(""),
                PSetExpression::Not(Box::new(PSetExpression::Compare(
                    CompleteStr("a"),
                    None,
                    CompleteStr("hello")
                )))
            ))
        );
    }

    #[test]
    fn test_comment_line() {
        assert_eq!(
            comment_line(CompleteStr("##hello Herman\n")),
            Ok((CompleteStr(""), CompleteStr("hello Herman")))
        );
        assert_eq!(
            comment_line(CompleteStr("##hello Herman\nHola")),
            Ok((CompleteStr("Hola"), CompleteStr("hello Herman")))
        );
        assert_eq!(
            comment_line(CompleteStr("##hello Herman!")),
            Ok((CompleteStr(""), CompleteStr("hello Herman!")))
        );
        assert_eq!(
            comment_line(CompleteStr("##hello\nHerman\n")),
            Ok((CompleteStr("Herman\n"), CompleteStr("hello")))
        );
        assert!(comment_line(CompleteStr("hello\nHerman\n")).is_err());
    }

    #[test]
    fn test_comment_block() {
        assert_eq!(
            comment_block(CompleteStr("##hello Herman\n")),
            Ok((CompleteStr(""), vec![CompleteStr("hello Herman")]))
        );
        assert_eq!(
            comment_block(CompleteStr("##hello Herman!")),
            Ok((CompleteStr(""), vec![CompleteStr("hello Herman!")]))
        );
        assert_eq!(
            comment_block(CompleteStr("##hello\n##Herman\n")),
            Ok((
                CompleteStr(""),
                vec![CompleteStr("hello"), CompleteStr("Herman")]
            ))
        );
        assert!(comment_block(CompleteStr("hello Herman")).is_err());
    }

    #[test]
    fn test_metadata() {
        assert_eq!(
            metadata(CompleteStr("@key=\"value\"")),
            Ok((
                CompleteStr(""),
                PMetadata {
                    key: CompleteStr("key"),
                    value: PValue::String("value".to_string())
                }
            ))
        );
        assert_eq!(
            metadata(CompleteStr("@key = \"value\"")),
            Ok((
                CompleteStr(""),
                PMetadata {
                    key: CompleteStr("key"),
                    value: PValue::String("value".to_string())
                }
            ))
        );
    }

    #[test]
    fn test_escaped_string() {}

    #[test]
    fn test_alphanumeric() {
        assert_eq!(
            alphanumeric(CompleteStr("simple ")),
            Ok((CompleteStr(" "), CompleteStr("simple")))
        );
        assert_eq!(
            alphanumeric(CompleteStr("simple?")),
            Ok((CompleteStr("?"), CompleteStr("simple")))
        );
        assert_eq!(
            alphanumeric(CompleteStr("5impl3 ")),
            Ok((CompleteStr(" "), CompleteStr("5impl3")))
        );
        assert!(alphanumeric(CompleteStr("%imple ")).is_err());
    }

    #[test]
    fn test_parameter() {
        assert_eq!(
            parameter(CompleteStr("hello ")),
            Ok((
                CompleteStr(""),
                PParameter {
                    name: CompleteStr("hello"),
                    ptype: None,
                    default: None,
                }
            ))
        );
        assert_eq!(
            parameter(CompleteStr("string:hello ")),
            Ok((
                CompleteStr(""),
                PParameter {
                    name: CompleteStr("hello"),
                    ptype: Some(PType::TString),
                    default: None,
                }
            ))
        );
        assert_eq!(
            parameter(CompleteStr(" string : hello ")),
            Ok((
                CompleteStr(""),
                PParameter {
                    name: CompleteStr("hello"),
                    ptype: Some(PType::TString),
                    default: None,
                }
            ))
        );
        assert_eq!(
            parameter(CompleteStr(" string : hello=\"default\"")),
            Ok((
                CompleteStr(""),
                PParameter {
                    name: CompleteStr("hello"),
                    ptype: Some(PType::TString),
                    default: Some(PValue::String("default".to_string())),
                }
            ))
        );
    }

    #[test]
    fn test_object_def() {
        assert_eq!(
            object_def(CompleteStr("object hello()")),
            Ok((
                CompleteStr(""),
                PObjectDef {
                    name: CompleteStr("hello"),
                    parameters: vec![]
                }
            ))
        );
        assert_eq!(
            object_def(CompleteStr("object  hello2 ( )")),
            Ok((
                CompleteStr(""),
                PObjectDef {
                    name: CompleteStr("hello2"),
                    parameters: vec![]
                }
            ))
        );
        assert_eq!(
            object_def(CompleteStr("object hello (string: p1, p2)")),
            Ok((
                CompleteStr(""),
                PObjectDef {
                    name: CompleteStr("hello"),
                    parameters: vec![
                        PParameter {
                            name: CompleteStr("p1"),
                            ptype: Some(PType::TString),
                            default: None,
                        },
                        PParameter {
                            name: CompleteStr("p2"),
                            ptype: None,
                            default: None,
                        }
                    ]
                }
            ))
        );
    }

    #[test]
    fn test_value() {
        // TODO other types
        assert_eq!(
            typed_value(CompleteStr("\"\"\"This is a string\"\"\"")),
            Ok((
                CompleteStr(""),
                PValue::String("This is a string".to_string())
            ))
        );
        assert_eq!(
            typed_value(CompleteStr("\"This is a string bis\"")),
            Ok((
                CompleteStr(""),
                PValue::String("This is a string bis".to_string())
            ))
        );
    }

    #[test]
    fn test_headers() {
        assert_eq!(
            header(CompleteStr("#!/bin/bash\n@format=1\n")),
            Ok((CompleteStr(""), PHeader { version: 1 }))
        );
        assert_eq!(
            header(CompleteStr("@format=21\n")),
            Ok((CompleteStr(""), PHeader { version: 21 }))
        );
        assert!(header(CompleteStr("@format=21.5\n")).is_err());
    }

    #[test]
    fn test_object_ref() {
        assert_eq!(
            object_ref(CompleteStr("hello()")),
            Ok((
                CompleteStr(""),
                PObjectRef {
                    name: CompleteStr("hello"),
                    parameters: vec![]
                }
            ))
        );
        assert_eq!(
            object_ref(CompleteStr("hello3 ")),
            Ok((
                CompleteStr(""),
                PObjectRef {
                    name: CompleteStr("hello3"),
                    parameters: vec![]
                }
            ))
        );
        assert_eq!(
            object_ref(CompleteStr("hello ( \"p1\", \"p2\" )")),
            Ok((
                CompleteStr(""),
                PObjectRef {
                    name: CompleteStr("hello"),
                    parameters: vec![
                        PValue::String("p1".to_string()),
                        PValue::String("p2".to_string())
                    ]
                }
            ))
        );
        assert_eq!(
            object_ref(CompleteStr("hello2 ( )")),
            Ok((
                CompleteStr(""),
                PObjectRef {
                    name: CompleteStr("hello2"),
                    parameters: vec![]
                }
            ))
        );
    }

    #[test]
    fn test_statement() {
        assert_eq!(
            statement(CompleteStr("object().state()")),
            Ok((
                CompleteStr(""),
                PStatement::StateCall(
                    None,
                    PCallMode::Enforce,
                    PObjectRef {
                        name: CompleteStr("object"),
                        parameters: vec![]
                    },
                    CompleteStr("state"),
                    vec![]
                )
            ))
        );
        assert_eq!(
            statement(CompleteStr("object().state( \"p1\", \"p2\")")),
            Ok((
                CompleteStr(""),
                PStatement::StateCall(
                    None,
                    PCallMode::Enforce,
                    PObjectRef {
                        name: CompleteStr("object"),
                        parameters: vec![]
                    },
                    CompleteStr("state"),
                    vec![
                        PValue::String("p1".to_string()),
                        PValue::String("p2".to_string())
                    ]
                )
            ))
        );
        assert_eq!(
            statement(CompleteStr("##hello Herman\n")),
            Ok((
                CompleteStr(""),
                PStatement::Comment(vec![CompleteStr("hello Herman")])
            ))
        );
    }

    #[test]
    fn test_declaration() {
        assert_eq!(
            declaration(CompleteStr("ntp state configuration ()\n{\n  file(\"/tmp\").permissions(\"root\", \"root\", \"g+w\")\n}\n")),
            Ok((CompleteStr("\n"),
                PDeclaration::State(PStateDef {
                    name: CompleteStr("configuration"),
                    object_name: CompleteStr("ntp"),
                    parameters: vec![],
                    statements: vec![
                        PStatement::StateCall(
                            None, PCallMode::Enforce,
                            PObjectRef {
                                name: CompleteStr("file"), 
                                parameters: vec![PValue::String("/tmp".to_string())] }, 
                            CompleteStr("permissions"),
                            vec![PValue::String("root".to_string()), PValue::String("root".to_string()), PValue::String("g+w".to_string())]
                        )
                    ]
                })
            )));
    }

    #[test]
    fn test_state() {
        assert_eq!(
            state(CompleteStr("object state configuration() {}")),
            Ok((
                CompleteStr(""),
                PStateDef {
                    name: CompleteStr("configuration"),
                    object_name: CompleteStr("object"),
                    parameters: vec![],
                    statements: vec![]
                }
            ))
        );
    }
}
