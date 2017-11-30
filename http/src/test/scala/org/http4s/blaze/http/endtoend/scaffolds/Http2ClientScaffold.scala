package org.http4s.blaze.http.endtoend.scaffolds

import java.nio.ByteBuffer

import org.http4s.blaze.http.http2.{DefaultFlowStrategy, Http2ClientConnection, Http2Settings}
import org.http4s.blaze.http.{HttpClientConfig, HttpClientImpl, HttpRequest, HttpResponsePrelude}
import org.http4s.blaze.http.http2.client.{Http2ClientSessionManagerImpl, Http2TlsClientHandshaker}
import org.http4s.blaze.pipeline.{Command, HeadStage, LeafBuilder}
import org.http4s.blaze.util.Execution

import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration._

class Http2ClientScaffold extends ClientScaffold(2, 0) {

  private[this] implicit val ec = Execution.trampoline

  private val underlying = {
    val h2Settings = Http2Settings.default.copy(
      initialWindowSize = 256 * 1024)

    val manager = new Http2ClientSessionManagerImpl(HttpClientConfig.Default, h2Settings) {
      override protected def initialPipeline(head: HeadStage[ByteBuffer]): Future[Http2ClientConnection] = {
        val p = Promise[Http2ClientConnection]

        // TODO: we need a better model for these
        val mySettings = h2Settings.copy()

        val flowStrategy = new DefaultFlowStrategy(mySettings)
        val handshaker = new Http2TlsClientHandshaker(mySettings, flowStrategy, Execution.trampoline)
        p.completeWith(handshaker.clientSession)
        LeafBuilder(handshaker).base(head)

        head.sendInboundCommand(Command.Connected)
        p.future.onComplete { r =>
        }(Execution.directec)
        p.future
      }
    }
    new HttpClientImpl(manager)
  }

  override def close(): Unit = {
    underlying.close()
    ()
  }

  // Not afraid to block: this is for testing.
  override def runRequest(request: HttpRequest): (HttpResponsePrelude, Array[Byte]) = {
    val fResponse = underlying(request) { resp =>
      val prelude = HttpResponsePrelude(resp.code, resp.status, resp.headers)
      resp.body.accumulate().map { body =>
        val arr = new Array[Byte](body.remaining)
        body.get(arr)
        prelude -> arr
      }
    }

    Await.result(fResponse, 10.seconds)
  }
}