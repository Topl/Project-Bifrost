package co.topl.program

import co.topl.attestation.{PublicKeyPropositionCurve25519, SignatureCurve25519}
import co.topl.utils.Gzip
import com.oracle.js.parser.ir.visitor.NodeVisitor
import com.oracle.js.parser.ir.{FunctionNode, LexicalContext, Node, VarNode}
import com.oracle.js.parser.{
  ErrorManager,
  Lexer,
  ScriptEnvironment,
  Source,
  Token,
  TokenStream,
  TokenType,
  Parser => GraalParser
}
import io.circe._
import io.circe.syntax._
import org.graalvm.polyglot.Context
import scorex.util.encode.Base64

import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.matching.Regex

/**
 * Created by Matt Kindy on 7/27/2017.
 */
case class ProgramPreprocessor(
  name:      String,
  initjs:    String,
  interface: Map[String, Seq[String]],
  //state: Json,
  variables: Json,
  code:      Map[String, String],
  signed:    Option[(PublicKeyPropositionCurve25519, SignatureCurve25519)]
)

object ProgramPreprocessor {

  val objectAssignPolyfill: String =
    s"""
       |if (typeof Object.assign != 'function') {
       |  // Must be writable: true, enumerable: false, configurable: true
       |  Object.defineProperty(Object, "assign", {
       |    value: function assign(target, varArgs) { // .length of function is 2
       |      'use strict';
       |      if (target == null) { // TypeError if undefined or null
       |        throw new TypeError('Cannot convert undefined or null to object');
       |      }
       |
       |      var to = Object(target);
       |
       |      for (var index = 1; index < arguments.length; index++) {
       |        var nextSource = arguments[index];
       |
       |        if (nextSource != null) { // Skip over if undefined or null
       |          for (var nextKey in nextSource) {
       |            // Avoid bugs when hasOwnProperty is shadowed
       |            if (Object.prototype.hasOwnProperty.call(nextSource, nextKey)) {
       |              to[nextKey] = nextSource[nextKey];
       |            }
       |          }
       |        }
       |      }
       |      return to;
       |    },
       |    writable: true,
       |    configurable: true
       |  });
       |}
     """.stripMargin

  /* TODO sanitise inputs!! */

  def apply(modulePath: Path)(args: JsonObject): ProgramPreprocessor = {

    /* Read file from path, expect JSON */
    val parsed = io.circe.parser.parse(new String(Files.readAllBytes(modulePath)))

    parsed match {
      case Left(f)     => throw f
      case Right(json) => wrapperFromJson(json, args)
    }
  }

  def apply(name: String, initjs: String, signed: Option[(PublicKeyPropositionCurve25519, SignatureCurve25519)] = None)(
    args:         JsonObject
  ): ProgramPreprocessor = {

    //val modifiedInitjs = initjs.replaceFirst("\\{", "\\{\n" + ValkyrieFunctions().reserved + "\n")
    //println(">>>>>>>>>>>>>>>>>>>>> initjs + reservedFunctions: " + modifiedInitjs)

    val (interface, /*cleanModuleState,*/ variables, code) = deriveFromInit(initjs /*modifiedInitjs*/, name)(args)

    ProgramPreprocessor(
      name,
      initjs /*modifiedInitjs*/,
      interface, /*parse(cleanModuleState).right.getOrElse(JsonObject.empty.asJson),*/ variables,
      code,
      signed
    )
  }

  private def wrapperFromJson(json: Json, args: JsonObject): ProgramPreprocessor = {
    /* Expect name to be top level */
    val name: String = (json \\ "module_name").head.asString.get

    /* Expect initjs to be top level and load it up */

    val initjs: String = {
      val cleanInitjs: String = (json \\ "initjs").head.asString.get
      //val modifiedInitjs = cleanInitjs.replaceFirst("\\{", "\\{\n" + ValkyrieFunctions().reserved + "\n")
      //println(">>>>>>>>>>>>>>>>>>>>> initjs + reservedFunctions: " + modifiedInitjs)
      //modifiedInitjs
      cleanInitjs
    }

    val announcedRegistry: Option[Map[String, Seq[String]]] =
      (json \\ "interface").headOption.map(_.as[Map[String, Seq[String]]] match {
        case Right(re) => re
        case Left(ex)  => throw ex
      })

    val signed: Option[(PublicKeyPropositionCurve25519, SignatureCurve25519)] = (json \\ "signed").headOption
      .map(_.as[(String, String)] match { case Right(re) => re; case Left(ex) => throw ex })
      .map { pair =>
        val pub = PublicKeyPropositionCurve25519(pair._1)
        val sig = SignatureCurve25519(pair._2)
        pub -> sig
      }

    val (interface, /*cleanModuleState,*/ variables, code) = deriveFromInit(initjs, name, announcedRegistry)(args)

    ProgramPreprocessor(name, initjs, interface, /*parse(cleanModuleState).right.get,*/ variables, code, signed)
  }

  //noinspection ScalaStyle
  private def deriveFromInit(initjs: String, name: String, announcedRegistry: Option[Map[String, Seq[String]]] = None)(
    args:                            JsonObject
  ): (Map[String, Seq[String]], /*String,*/ Json, Map[String, String]) = {

    val jsre: Context = Context.create("js")

    /*jsre.eval("js", objectAssignPolyfill)
    jsre.eval("js", initjs)
    jsre.eval("js", s"var c = $name.fromJSON('${args.asJson.noSpaces}')")
    println(s">>>>>>>> var c: ")
    jsre.eval("js", "for(property in c) { print(property) }")
    val cleanModuleState: Value = jsre.eval("js", s"$name.toJSON(c)") //.asInstanceOf[String]
    val cms = cleanModuleState.getSourceLocation
    println(cms)
     */

    /* Interpret interface from object */
    /* val esprimajs: InputStream = classOf[ProgramPreprocessor].getResourceAsStream("/esprima.js")
    jsre.eval(new InputStreamReader(esprimajs))*/

    /*val defineEsprimaFnParamParser =
      s"""
        |function getParameters(f) {
        |    var parsed = esprima.parse("safetyValve = " + f.toString().replace("[native code]", ""));
        |    var params = parsed.body[0].expression.right.params;
        |    var ret = [];
        |    params.forEach(function(p){ ret.push(p.name); })
        |    return ret;
        |}
      """.stripMargin

    jsre.eval(defineEsprimaFnParamParser)*/

    //println(s">>>>>>>>>>> Registry: $interface")

    //val variables: Seq[String] = deriveState(jsre, initjs)
    //val code: Seq[String] = deriveFunctions(jsre, initjs)

    val variables: Json = deriveState(jsre, initjs)

    val code: Map[String, String] = deriveFunctions(jsre, initjs)

    val interface = if (announcedRegistry.isDefined && checkRegistry(jsre, announcedRegistry.get)) {
      announcedRegistry.get
    } else {
      val interfaceRes = deriveRegistry(jsre, initjs)

      code.keySet.zip(interfaceRes).toMap
    }

    (interface, /*cleanModuleState,*/ variables, code)
  }

  private def checkRegistry(jsre: Context, announcedRegistry: Map[String, Seq[String]]): Boolean =
    announcedRegistry.keySet.forall { k =>
      jsre
        .eval(
          "js",
          s"""
           |typeof c.$k === "function" ? getParameters(c.$k).length === ${announcedRegistry(k).size} : false
         """.stripMargin
        )
        .asInstanceOf[Boolean]
    }

  private def deriveRegistry(jsre: Context, initjs: String): Seq[Seq[String]] = {

    def commentTokenSource(source: Source): Seq[String] = {

      val commentList: ListBuffer[String] = new ListBuffer[String]()

      val tokenStream: TokenStream = new TokenStream

      val lexer = new Lexer(source, tokenStream, false, 8, false, false, true)

      lexer.lexify()

      for (i <- 0 until tokenStream.last()) {
        val token = tokenStream.get(i)
        if (
          Token.descType(tokenStream.get(i)) == TokenType.COMMENT &&
          Token.descType(tokenStream.get(i + 4)) == TokenType.FUNCTION
        ) {
          commentList += source.getString(token)
        }
      }

      commentList.toSeq
    }

    def paramTypes(commentString: Seq[String]): Seq[Seq[String]] = {

      val pattern: Regex = """(?<=@param \{)[A-Za-z]+(?=\})""".r

      commentString.map { str =>
        pattern.findAllIn(str).toSeq
      }
    }

    paramTypes(commentTokenSource(Source.sourceFor("initjs", initjs)))
  }

  //noinspection ScalaStyle
  private def deriveState(jsre: Context, initjs: String): Json = {
    //val initjsStr = s"\'${initjs.replaceAll("\n", "\\\\n").trim}\'"

    val scriptEnv: ScriptEnvironment = ScriptEnvironment.builder
      .ecmaScriptVersion(8)
      .constAsVar(false)
      .emptyStatements(false)
      .syntaxExtensions(true)
      .scripting(false)
      .shebang(false)
      .strict(true)
      .functionStatementBehavior(ScriptEnvironment.FunctionStatementBehavior.ERROR)
      .build()

    val errManager = new ErrorManager.ThrowErrorManager
    val src = Source.sourceFor("script", initjs)
    val parser: GraalParser = new GraalParser(scriptEnv, src, errManager)
    val parsed = parser.parse()

    def varList(node: FunctionNode): Json = {

      val vars = scala.collection.mutable.Map[String, String]()
      val varJson = scala.collection.mutable.Map[String, Json]()

      node.getBody.accept(new NodeVisitor[LexicalContext](new LexicalContext) {

        override def leaveVarNode(varNode: VarNode): VarNode = {
          val name: String = varNode.getName.getName
          val init = varNode.getInit.toString
          vars += (name -> init)
          varNode
        }

      })

      jsre.eval("js", initjs)

      vars.map { v =>
        jsre.eval("js", s"typeof ${v._1}").toString match {
          case "number" => varJson += (v._1 -> JsonNumber.fromString(v._2).get.asJson)
          case "string" => varJson += (v._1 -> v._2.asJson)
          case _        => throw new ClassCastException("Not a valid JavaScript type")
        }
      }
      varJson.toMap.asJson
    }

    varList(parsed)
  }

  private def deriveFunctions(jsre: Context, initjs: String): Map[String, String] = {

    val scriptEnv: ScriptEnvironment = ScriptEnvironment.builder
      .ecmaScriptVersion(8)
      .constAsVar(false)
      .emptyStatements(false)
      .syntaxExtensions(true)
      .scripting(false)
      .shebang(false)
      .strict(true)
      .functionStatementBehavior(ScriptEnvironment.FunctionStatementBehavior.ERROR)
      .build()

    val errManager = new ErrorManager.ThrowErrorManager
    val src = Source.sourceFor("script", initjs)
    val parser: GraalParser = new GraalParser(scriptEnv, src, errManager)
    val parsed = parser.parse()

    def functionList(node: FunctionNode): Map[String, String] = {

      val functions = mutable.LinkedHashMap[String, String]()

      node.getBody.accept(new NodeVisitor[LexicalContext](new LexicalContext) {

        override def leaveFunctionNode(functionNode: FunctionNode): Node = {
          functions += functionNode.getName -> s"$functionNode = {${functionNode.getBody}}"
          functionNode
        }
      })
      functions.toMap
    }

    functionList(parsed)
  }

  implicit val encodeTerms: Encoder[ProgramPreprocessor] = (p: ProgramPreprocessor) =>
    Map(
      //"state" -> Base64.encode(Gzip.encode(ByteString(state.noSpaces.getBytes)).toArray[Byte]).asJson,
      "name"      -> p.name.asJson,
      "initjs"    -> Base64.encode(Gzip.compress(p.initjs.getBytes)).asJson,
      "interface" -> p.interface.map(a => a._1 -> a._2.map(_.asJson).asJson).asJson,
      "variables" -> p.variables.asJson,
      "code"      -> p.code.map(a => a._1 -> a._2).asJson,
      "signed"    -> p.signed.map(pair => pair._1.toString -> pair._2.toString).asJson
    ).asJson

  implicit val decodeTerms: Decoder[ProgramPreprocessor] = (c: HCursor) =>
    for {
      //state <- c.downField("state").as[String]
      name      <- c.downField("name").as[String]
      initjs    <- c.downField("initjs").as[String]
      interface <- c.downField("interface").as[Map[String, Seq[String]]]
      variables <- c.downField("variables").as[Json]
      code      <- c.downField("code").as[Map[String, String]]
      signed    <- c.downField("signed").as[Option[(String, String)]]
    } yield {

      def decodeGzip(zippedStr: String): String = {
        val zipped: Array[Byte] = Base64.decode(zippedStr).get
        val unzipped: Array[Byte] = Gzip.decompress(zipped)
        new String(unzipped)
      }

      ProgramPreprocessor(
        name,
        decodeGzip(initjs),
        interface,
        //parse(new String(decodedState.toArray[Byte])) match {case Right(re) => re; case Left(ex) => throw ex},
        variables,
        code,
        signed.map { pair =>
          val pub = PublicKeyPropositionCurve25519(pair._1)
          val sig = SignatureCurve25519(pair._2)
          pub -> sig
        }
      )
    }
}
