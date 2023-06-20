package example.mesh

import caliban.InputValue.{ ListValue, ObjectValue }
import caliban.interop.tapir.HttpInterpreter
import caliban.tools.{ Source, SttpClient }
import caliban.{ ResponseValue, ZHttpAdapter }
import sttp.client3.httpclient.zio.HttpClientZioBackend
import sttp.tapir.json.circe._
import zio._
import zio.http._

object Gateway extends ZIOAppDefault {
  val stores: Source[SttpClient]  = Source.graphQL("http://localhost:8081/api/graphql")
  val books: Source[SttpClient]   = Source.graphQL("http://localhost:8082/api/graphql")
  val authors: Source[SttpClient] = Source.graphQL("http://localhost:8083/api/graphql")

  val gateway = stores
//    .transform(Transformer.FilterField {
//      case "bookSells" => false
//      case _           => true
//    })
    .extend(
      stores,
      sourceFieldName = "bookSells",
      targetTypeName = "Store",
      targetFieldName = "bookSells",
      argumentMapping = Map("id" -> ("storeId" -> _))
    )
    .extend(
      books,
      sourceFieldName = "book",
      targetTypeName = "Sells",
      targetFieldName = "book",
      argumentMapping = Map("bookId" -> ("id" -> _))
    )
    .extend(
      authors,
      sourceFieldName = "authors_v1_AuthorsService_GetAuthors",
      targetTypeName = "Book",
      targetFieldName = "author",
      argumentMapping = Map("authorId" -> (v => "input" -> ObjectValue(Map("ids" -> ListValue(List(v)))))),
      filterBatchedValues = (result, field) =>
        result.asListValue.fold(result)(list =>
          ResponseValue.ListValue(list.values.filter {
            case ResponseValue.ObjectValue(fields) =>
              fields.toMap
                .get("id")
                .map(v => Map("input" -> ObjectValue(Map("ids" -> ListValue(List(v.toInputValue))))))
                .contains(field.arguments)
            case _                                 => true
          })
        )
    )

  def run: Task[Unit] =
    gateway.toGraphQL
      .flatMap(_.interpreter)
      .flatMap(interpreter =>
        Server.serve(
          Http.collectHttp[Request] { case _ -> !! / "api" / "graphql" =>
            ZHttpAdapter.makeHttpService(HttpInterpreter(interpreter))
          }
        )
      )
      .provide(ZLayer.succeed(Server.Config.default.port(8080)), Server.live, HttpClientZioBackend.layer())
}