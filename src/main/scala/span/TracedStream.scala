package span

import cats.syntax.all.*
import cats.{Applicative, Monoid}
import fs2.*
import org.typelevel.otel4s.context.propagation.TextMapGetter
import org.typelevel.otel4s.trace.*

/**
 * Propagate the span via a tuple. Tracer must be injected for each stream method
 */
opaque type TracedStream[F[_], A] = Stream[F, (Span[F], A)]

extension [F[_], A](stream: Stream[F, A]) {
  def inject[C: TextMapGetter](span: SpanOps[F], carrier: C)(using F: Applicative[F], tracer: Tracer[F]): TracedStream[F, A] =
    Stream.bracket(tracer.joinOrRoot(carrier)(span.startUnmanaged))(_.end).flatMap { span =>
      stream.evalMapChunk(a => Applicative[F].pure(span -> a))
    }

  def inject[C: TextMapGetter : Monoid](span: SpanOps[F])(using Applicative[F], Tracer[F]): TracedStream[F, A] =
    inject(span, Monoid[C].empty)

  def inject[C: TextMapGetter : Monoid](name: String, kind: SpanKind)(using F: Applicative[F], tracer: Tracer[F]): TracedStream[F, A] =
    inject(tracer.spanBuilder(name).withSpanKind(kind).build)
}


extension [F[_], A](stream: TracedStream[F, A]) {
  private def eval[B](f: A => F[B])(using F: Applicative[F], tracer: Tracer[F]): (Span[F], A) => F[(Span[F], B)] =
    (span, a) => tracer.childScope(span.context)(f(a).map(span -> _))

  def evalMapTrace[B](f: A => F[B])(using F: Applicative[F], tracer: Tracer[F]): TracedStream[F, B] =
    stream.evalMap(eval(f).tupled)

  def evalMapChunkTrace[B](f: A => F[B])(using F: Applicative[F], tracer: Tracer[F]): TracedStream[F, B] =
    stream.evalMapChunk(eval(f).tupled)

  def evalMap[B](f: A => F[B])(using Applicative[F]): TracedStream[F, B] =
    stream.evalMap((span, a) => f(a).tupleLeft(span))

  def evalMapChunk[B](f: A => F[B])(using Applicative[F]): TracedStream[F, B] =
    stream.evalMapChunk((span, a) => f(a).tupleLeft(span))

  def traceMapChunk[B](f: A => B)(using Applicative[F], Tracer[F]): TracedStream[F, B] =
    stream.evalMapChunk(eval(a => Applicative[F].pure(f(a))).tupled)

  def endTrace(using Applicative[F]): Stream[F, A] =
    stream.evalMapChunk((span, a) => span.end.as(a))

  def through[B](f: TracedStream[F, A] => TracedStream[F, B]): TracedStream[F, B] =
    f(stream)
}
