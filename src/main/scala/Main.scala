import cats.effect.*
import cats.syntax.all.*
import fs2.*
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.oteljava.context.Context
import org.typelevel.otel4s.trace.{SpanKind, Tracer}
import writer.*

import scala.concurrent.duration.*

object Main extends IOApp.Simple {

  def otelStream(tracer: Tracer[IO]): TracedStream[IO, Int] = {

    def pipe(stream: TracedStream[IO, Int]): TracedStream[IO, Int] =
      stream.evalMapTrace(i => IO(i * 10))

    Stream(1, 2, 3).covary[IO].inject[Map[String, String]]("overarching work", SpanKind.Consumer, tracer)
      .evalMap(IO(_)).evalMapTrace(i =>
        tracer.span("some work").surround(IO.sleep(1.second).as(i * 2))
      ).evalMapTrace(i =>
        tracer.span("some work again").surround(IO.sleep(1.second).as(i - 2))
      )
      .through(pipe)
      .evalMapTrace(_ =>
        tracer.span("I'm gonna error").surround(RuntimeException("Dead stream").raiseError[IO, Int])
      )
  }

  override def run: IO[Unit] =
    for {
      otel <- OtelJava.global[IO]
      tracer <- otel.tracerProvider.get("test-stream-tracer")
      _ <- otelStream(tracer).endTrace.debug().compile.drain
    } yield ()
}