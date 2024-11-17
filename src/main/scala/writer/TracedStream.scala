package writer

import cats.data.WriterT
import cats.syntax.all.*
import cats.{Applicative, Monoid}
import fs2.*
import org.typelevel.otel4s.context.propagation.TextMapGetter
import org.typelevel.otel4s.trace.*

/**
 * Use a [[WriterT]]. Span and Tracer only need to be injected once
 */
opaque type TracedStream[F[_], A] = WriterT[Stream[F, *], Context[F], A]

final case class Context[F[_]](spanContext: SpanContext, private val tracer: Tracer[F]) {
  def childScope[A](fa: F[A]): F[A] = tracer.childScope(spanContext)(fa)
}

extension [F[_], A](stream: Stream[F, A]) {
  def inject[C: TextMapGetter](span: SpanOps[F], carrier: C, tracer: Tracer[F])(using Applicative[F]): TracedStream[F, A] =
    WriterT(Stream.bracket(tracer.joinOrRoot(carrier)(span.startUnmanaged))(_.end).flatMap { span =>
      val context = Context(span.context, tracer)
      stream.evalMapChunk(Applicative[F].pure(_).tupleLeft(context))
    })

  def inject[C: TextMapGetter : Monoid](span: SpanOps[F], tracer: Tracer[F])(using Applicative[F]): TracedStream[F, A] =
    inject(span, Monoid[C].empty, tracer)

  def inject[C: TextMapGetter : Monoid](name: String, kind: SpanKind, tracer: Tracer[F])(using Applicative[F]): TracedStream[F, A] =
    inject(tracer.spanBuilder(name).withSpanKind(kind).build, tracer)

}


extension [F[_], A](stream: TracedStream[F, A]) {
  private def eval[B](f: A => F[B])(using F: Applicative[F]): (Context[F], A) => F[(Context[F], B)] =
    (context, a) => context.childScope(f(a).tupleLeft(context))

  def evalMapTrace[B](f: A => F[B])(using Applicative[F]): TracedStream[F, B] =
    WriterT(stream.run.evalMap(eval(f).tupled))

  def evalMapChunkTrace[B](f: A => F[B])(using Applicative[F]): TracedStream[F, B] =
    WriterT(stream.run.evalMapChunk(eval(f).tupled))

  def evalMap[B](f: A => F[B])(using Applicative[F]): TracedStream[F, B] =
    evalMapTrace(f)

  def evalMapChunk[B](f: A => F[B])(using Applicative[F]): TracedStream[F, B] =
    evalMapChunkTrace(f)

  def traceMapChunk[B](f: A => B)(using Applicative[F]): TracedStream[F, B] =
    WriterT(stream.run.evalMapChunk(eval(a => Applicative[F].pure(f(a))).tupled))

  def endTrace(using Applicative[F]): Stream[F, A] =
    stream.value

  def through[B](f: TracedStream[F, A] => TracedStream[F, B]): TracedStream[F, B] =
    f(stream)

}