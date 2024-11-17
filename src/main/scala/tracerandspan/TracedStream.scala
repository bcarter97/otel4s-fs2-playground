package tracerandspan

import cats.syntax.all.*
import cats.{Applicative, Monoid}
import fs2.*
import org.typelevel.otel4s.context.propagation.TextMapGetter
import org.typelevel.otel4s.trace.*

/**
 * Propagate the span via a tuple
 */
opaque type TracedStream[F[_], A] = Stream[F, (SpanContext, Tracer[F], A)]

extension [F[_], A](stream: Stream[F, A]) {
  def inject[C: TextMapGetter](span: SpanOps[F], carrier: C, tracer: Tracer[F])(using Applicative[F]): TracedStream[F, A] =
    Stream.bracket(tracer.joinOrRoot(carrier)(span.startUnmanaged))(_.end).flatMap { span =>
      stream.evalMapChunk(a => Applicative[F].pure((span.context, tracer, a)))
    }

  def inject[C: TextMapGetter : Monoid](span: SpanOps[F], tracer: Tracer[F])(using Applicative[F]): TracedStream[F, A] =
    inject(span, Monoid[C].empty, tracer)

  def inject[C: TextMapGetter : Monoid](name: String, kind: SpanKind, tracer: Tracer[F])(using Applicative[F]): TracedStream[F, A] =
    inject(tracer.spanBuilder(name).withSpanKind(kind).build, tracer)

}


extension [F[_], A](stream: TracedStream[F, A]) {
  private def eval[B](f: A => F[B])(using F: Applicative[F]): (SpanContext, Tracer[F], A) => F[(SpanContext, Tracer[F], B)] =
    (context, tracer, a) => tracer.childScope(context)(f(a).map(a => (context, tracer, a)))

  def evalMapTrace[B](f: A => F[B])(using Applicative[F]): TracedStream[F, B] =
    stream.evalMap(eval(f).tupled)

  def evalMapChunkTrace[B](f: A => F[B])(using Applicative[F]): TracedStream[F, B] =
    stream.evalMapChunk(eval(f).tupled)

  def evalMap[B](f: A => F[B])(using Applicative[F]): TracedStream[F, B] =
    stream.evalMap((span, tracer, a) => f(a).map(b => (span, tracer, b)))

  def evalMapChunk[B](f: A => F[B])(using Applicative[F]): TracedStream[F, B] =
    stream.evalMapChunk((span, tracer, a) => f(a).map(b => (span, tracer, b)))

  def traceMapChunk[B](f: A => B)(using Applicative[F]): TracedStream[F, B] =
    stream.evalMapChunk(eval(a => Applicative[F].pure(f(a))).tupled)

  def endTrace(using Applicative[F]): Stream[F, A] =
    stream.evalMapChunk((_, _, a) => Applicative[F].pure(a))

  def through[B](f: TracedStream[F, A] => TracedStream[F, B]): TracedStream[F, B] =
    f(stream)

}
