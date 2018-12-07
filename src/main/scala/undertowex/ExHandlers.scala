package undertow

import java.nio.ByteBuffer

import app.Logger
import io.undertow.Handlers._
import io.undertow.server._
import io.undertow.server.handlers.cache.DirectBufferCache
import io.undertow.server.handlers.encoding._
import io.undertow.server.handlers.resource.{CachingResourceManager, ClassPathResourceManager, ResourceHandler}
import io.undertow.util.Headers._
import io.undertow.util.HttpString
import io.undertow.util.StatusCodes._
import org.xnio.BufferAllocator
import play.api.libs.json.{Json, Writes}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object ExHandlers extends Logger {
  val HTML_CONTENT_TYPE = "text/html"
  val TEXT_CONTENT_TYPE = "text/plain"
  val JSON_CONTENT_TYPE = "application/json;charset=UTF-8"

  val cacheTime = 60 * 60 * 24

  case class Error(code: Int, key: String, message: String)

  implicit val errorFormat = Json.format[Error]

  def gzip(next: HttpHandler): EncodingHandler = new EncodingHandler(
    header(next, VARY_STRING, ACCEPT_ENCODING_STRING),
    new ContentEncodingRepository().addEncodingHandler(GZIP.toString, new GzipEncodingProvider, 1)
  )

  def secureHeaders(next: HttpHandler): HttpHandler =
    header(header(header(next, "x-content-type-options", "nosniff"), "x-frame-options", "SAMEORIGIN"), "x-xss-protection", "1; mode=block")

  def sendText(exchange: HttpServerExchange, value: String, headers: Map[HttpString, String] = Map()): HttpServerExchange = {
    exchange.getResponseHeaders.put(CONTENT_TYPE, TEXT_CONTENT_TYPE)
    headers.foreach { case (headerName, headerValue) => exchange.getResponseHeaders.put(headerName, headerValue) }
    exchange.getResponseSender.send(value)
    exchange.endExchange()
  }

  def sendContent(exchange: HttpServerExchange, buffer: ByteBuffer, contentType: String, headers: Map[HttpString, String] = Map()): Unit = {
    exchange.getResponseHeaders.put(CONTENT_TYPE, contentType)
    headers.foreach { case (headerName, headerValue) => exchange.getResponseHeaders.put(headerName, headerValue) }
    exchange.getResponseSender.send(buffer)
  }

  def sendJson[A <: AnyRef](
                             exchange: HttpServerExchange,
                             eventualA: Future[Option[A]],
                             notFoundMessageKey: String = "not_found",
                             notFoundMessage: String = NOT_FOUND_STRING
                           )(
                             implicit
                             executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global,
                             tjs: Writes[A]
                           ): Unit =
    eventualA.onComplete {
      case Success(Some(a)) => sendJson(exchange, a)
      case Success(None) => sendError(exchange, NOT_FOUND, notFoundMessageKey, notFoundMessage)
      case Failure(e) => handleError(exchange, e)
    }

  def sendJson[A <: AnyRef](exchange: HttpServerExchange, a: A)(implicit tjs: Writes[A]) = {
    exchange.getResponseHeaders.put(CONTENT_TYPE, JSON_CONTENT_TYPE)
    exchange.getResponseSender.send(Json.toJson(a).toString())
  }

  def sendJsonNoContent(exchange: HttpServerExchange): Unit = {
    sendJson(exchange, NO_CONTENT)
  }

  def sendJsonCreated(exchange: HttpServerExchange): Unit = {
    sendJson(exchange, CREATED)
  }

  private def sendJson(exchange: HttpServerExchange, statusCode: Int): Unit = {
    exchange.getResponseHeaders.put(CONTENT_TYPE, JSON_CONTENT_TYPE)
    exchange.setStatusCode(statusCode)
    exchange.endExchange()
  }

  def handleError[A <: AnyRef](exchange: HttpServerExchange, e: Throwable): HttpServerExchange = {
    e match {
      case _: IllegalArgumentException =>
        log.error(s"Retrieving eventual result failed", e)
        sendError(exchange, BAD_REQUEST, e.getMessage)
      case _ =>
        log.error(s"Retrieving eventual result failed", e)
        sendError(exchange, INTERNAL_SERVER_ERROR)
    }
  }

  def sendError(exchange: HttpServerExchange, statusCode: Int) = {
    exchange.getResponseHeaders.put(CONTENT_TYPE, JSON_CONTENT_TYPE)
    exchange.setStatusCode(statusCode)
    exchange.endExchange()
  }

  def sendError(exchange: HttpServerExchange, statusCode: Int, message: String): HttpServerExchange = {
    sendError(exchange, statusCode, getReason(statusCode), message)
  }

  def sendError(exchange: HttpServerExchange, statusCode: Int, key: String, message: String): HttpServerExchange = {
    exchange.setStatusCode(statusCode)
    sendJson(exchange, Error(statusCode, key, message))
    exchange.endExchange()
  }

  def assets(prefix: String): ResourceHandler = resource(createCachingResourceManager(prefix))

  def createCachingResourceManager(prefix: String): CachingResourceManager = {
    val resourceManager = new ClassPathResourceManager(this.getClass.getClassLoader, prefix)
    val directBufferCache = new DirectBufferCache(1024, 1024, 1024 * 1024, BufferAllocator.DIRECT_BYTE_BUFFER_ALLOCATOR, cacheTime)
    new CachingResourceManager(1024 * 1024, 10L * 1024L * 1024L, directBufferCache, resourceManager, cacheTime)
  }

}
