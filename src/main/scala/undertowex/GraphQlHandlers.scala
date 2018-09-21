package undertowex

import io.undertow.server.{HttpHandler, HttpServerExchange}
import io.undertow.util.StatusCodes._
import models.SchemaDefinition
import play.api.libs.json._
import sangria.execution.{ErrorWithResolver, Executor, QueryAnalysisError}
import sangria.introspection.introspectionQuery
import sangria.marshalling.playJson._
import sangria.parser.{QueryParser, SyntaxError}
import sangria.renderer.SchemaRenderer

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}
import undertow.ExHandlers._

object GraphQlHandlers {


  def getParam(exchange: HttpServerExchange, field: String): Option[String] =
    for {
      parameters <- Option(exchange.getQueryParameters)
      values <- Option(parameters.get(field))
      head <- Option(values.peek())
    } yield head

  private def parseVariables(variables: String) =
    if (variables.trim == "" || variables.trim == "null") Json.obj() else Json.parse(variables).as[JsObject]

  def sendError(exchange: HttpServerExchange, statusCode: Int, message: String): HttpServerExchange = {
    sendError(exchange, statusCode, getReason(statusCode), message)
  }

  def sendError(exchange: HttpServerExchange, statusCode: Int, key: String, message: String): HttpServerExchange = {
    exchange.setStatusCode(statusCode)
    exchange.getResponseSender.send(message)
    exchange.endExchange()
  }

  class GraphqlHandler extends HttpHandler {
    def handleRequest(exchange: HttpServerExchange): Unit = {
      val parameters = exchange.getQueryParameters
      if (parameters.containsKey("query")) {
        val query = parameters.get("query").peek()
        val variables = Option(parameters.get("variables")).map(_.peek()) map parseVariables
        val operation = Option(parameters.get("operation")).map(_.peek())
        executeQuery(exchange, query, variables, operation)
      } else {
        sendError(exchange, BAD_REQUEST, "Query parameter is missing")
      }
    }
  }

  class GraphqlBodyHandler extends HttpHandler {
    def handleRequest(exchange: HttpServerExchange): Unit = {
      exchange.startBlocking()
      val json = Json.parse(exchange.getInputStream)

      val query = (json \ "query").as[String]
      val operation = (json \ "operationName").asOpt[String]

      val variables = (json \ "variables").toOption.flatMap {
        case JsString(vars) ⇒ Some(parseVariables(vars))
        case obj: JsObject ⇒ Some(obj)
        case _ ⇒ None
      }

      executeQuery(exchange, query, variables, operation)
    }
  }

  class GraphqlSchemaHandler extends HttpHandler {
    def handleRequest(exchange: HttpServerExchange): Unit = {
      exchange.startBlocking()
      sendText(exchange, SchemaRenderer.renderSchema(SchemaDefinition.StarWarsSchema))
    }
  }

  class GraphqlJsonSchemaHandler extends HttpHandler {
    def handleRequest(exchange: HttpServerExchange): Unit = {
      exchange.startBlocking()
      val futureOfSchemaJson = Executor.execute(SchemaDefinition.StarWarsSchema, introspectionQuery)

      futureOfSchemaJson.onComplete(_ match {
        case Success(schema) => sendJson(exchange, schema)
        case Failure(t) => {
          log.error("Could not generate graphql json schema", t)
          sendError(exchange, 500, t.getMessage)
        }
      })
    }
  }

  private def executeQuery(exchange: HttpServerExchange, query: String, variables: Option[JsObject], operation: Option[String]): Unit =
    QueryParser.parse(query) match {

      // query parsed successfully, time to execute it!
      case Success(queryAst) =>
        Executor.execute(
          schema = SchemaDefinition.StarWarsSchema,
          queryAst = queryAst,
          operationName = operation,
          variables = variables getOrElse Json.obj(),
          maxQueryDepth = Some(15)
        )
          .map(result => sendJson(exchange, result))
          .recover {
            case error: QueryAnalysisError ⇒ {
              log.error(s"Invalid request ${error.resolveError}")
              exchange.setStatusCode(BAD_REQUEST)
              sendJson(exchange, error.resolveError)
            }
            case error: ErrorWithResolver ⇒ {
              log.error(s"Couldn't handle request ${error.resolveError}")
              exchange.setStatusCode(INTERNAL_SERVER_ERROR)
              sendJson(exchange, error.resolveError)
            }
          }

      // can't parse GraphQL query, return error
      case Failure(error: SyntaxError) ⇒
        log.error(s"Invalid request ${error.getMessage()} because of  ${error.originalError}")
        exchange.setStatusCode(BAD_REQUEST)
        sendJson(exchange, Json.obj(
          "syntaxError" → error.getMessage,
          "locations" → Json.arr(Json.obj(
            "line" → error.originalError.position.line,
            "column" → error.originalError.position.column
          ))
        ))

      case Failure(error) ⇒ {
        log.error("Error while handling request", error)
        sendError(exchange, 500, error.getMessage)
      }
    }
}
