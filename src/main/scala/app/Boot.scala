package app

import java.lang.Boolean.TRUE

import io.undertow.Handlers.routing
import javax.activation.{CommandMap, MailcapCommandMap}
import io.undertow.{Handlers, Undertow}
import io.undertow.UndertowOptions.RECORD_REQUEST_START_TIME
import io.undertow.server.{HttpHandler, HttpServerExchange}
import io.undertow.server.handlers.accesslog.{AccessLogHandler, JBossLoggingAccessLogReceiver}
import io.undertow.util.Headers
import io.undertow.util.Methods.{OPTIONS, POST}
import undertowex.GraphQlHandlers._

object Boot {

  def main(args: Array[String]): Unit = {
    import undertow.ExHandlers._
    import Handlers._
    routing.get("/graphql", new GraphqlHandler())
      .post("/graphql", new GraphqlBodyHandler())
      .get("/render-schema", new GraphqlSchemaHandler())
      .get("/render-json-schema", new GraphqlJsonSchemaHandler())
    val handler = new HttpHandler {
        def handleRequest(exchange: HttpServerExchange) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
        exchange.getResponseSender().send("Hello World");
      }
    }

    Undertow.builder
      .addHttpListener(8081, "localhost")
      .setHandler(handler)
      .build
      .start()
  }
}

