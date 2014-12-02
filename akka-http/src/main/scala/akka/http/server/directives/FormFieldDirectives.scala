/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server
package directives

import scala.concurrent.{ Future, ExecutionContext }
import scala.util.{ Failure, Success }
import akka.http.unmarshalling.Unmarshaller.UnsupportedContentTypeException
import akka.http.common._
import akka.http.util._
import FastFuture._

trait FormFieldDirectives extends ToNameReceptacleEnhancements {
  import FormFieldDirectives._

  /**
   * Rejects the request if the defined form field matcher(s) don't match.
   * Otherwise the form field value(s) are extracted and passed to the inner route.
   */
  def formField(pdm: FieldMagnet): pdm.Out = pdm()

  /**
   * Rejects the request if the defined form field matcher(s) don't match.
   * Otherwise the form field value(s) are extracted and passed to the inner route.
   */
  def formFields(pdm: FieldMagnet): pdm.Out = pdm()

}

object FormFieldDirectives extends FormFieldDirectives {
  sealed trait FieldMagnet {
    type Out
    def apply(): Out
  }
  object FieldMagnet {
    implicit def apply[T](value: T)(implicit fdef: FieldDef[T]) =
      new FieldMagnet {
        type Out = fdef.Out
        def apply() = fdef(value)
      }
  }

  sealed trait FieldDef[T] {
    type Out
    def apply(value: T): Out
  }

  object FieldDef {
    def fieldDef[A, B](f: A ⇒ B) =
      new FieldDef[A] {
        type Out = B
        def apply(value: A) = f(value)
      }

    import akka.http.unmarshalling.{ FromStrictFormFieldUnmarshaller ⇒ FSFFU, _ }
    import BasicDirectives._
    import RouteDirectives._
    import FutureDirectives._
    type SFU = FromEntityUnmarshaller[StrictForm]
    type FSFFOU[T] = Unmarshaller[Option[StrictForm.Field], T]

    //////////////////// "regular" formField extraction ////////////////////

    private def extractField[A, B](f: A ⇒ Directive1[B]) = fieldDef(f)
    private def fieldOfForm[T](fieldName: String, fu: Unmarshaller[Option[StrictForm.Field], T])(implicit sfu: SFU): RequestContext ⇒ Future[T] = { ctx ⇒
      import ctx.executionContext
      sfu(ctx.request.entity).fast.flatMap(form ⇒ fu(form field fieldName))
    }
    private def filter[T](fieldName: String, fu: FSFFOU[T])(implicit sfu: SFU): Directive1[T] = {
      extract(fieldOfForm(fieldName, fu)).flatMap {
        onComplete(_).flatMap {
          case Success(x)                                  ⇒ provide(x)
          case Failure(Unmarshaller.NoContentException)    ⇒ reject(MissingFormFieldRejection(fieldName))
          case Failure(x: UnsupportedContentTypeException) ⇒ reject(UnsupportedRequestContentTypeRejection(x.supported))
          case Failure(x)                                  ⇒ reject(MalformedFormFieldRejection(fieldName, x.getMessage.nullAsEmpty, Option(x.getCause)))
        }
      }
    }
    implicit def forString(implicit sfu: SFU, fu: FSFFU[String]) =
      extractField[String, String] { fieldName ⇒ filter(fieldName, fu) }
    implicit def forSymbol(implicit sfu: SFU, fu: FSFFU[String]) =
      extractField[Symbol, String] { symbol ⇒ filter(symbol.name, fu) }
    implicit def forNR[T](implicit sfu: SFU, fu: FSFFU[T]) =
      extractField[NameReceptacle[T], T] { nr ⇒ filter(nr.name, fu) }
    implicit def forNUR[T](implicit sfu: SFU) =
      extractField[NameUnmarshallerReceptacle[T], T] { nr ⇒ filter(nr.name, StrictForm.Field.unmarshallerFromFSU(nr.um)) }
    implicit def forNOR[T](implicit sfu: SFU, fu: FSFFOU[T], ec: ExecutionContext) =
      extractField[NameOptionReceptacle[T], Option[T]] { nr ⇒ filter[Option[T]](nr.name, fu) }
    implicit def forNDR[T](implicit sfu: SFU, fu: FSFFOU[T], ec: ExecutionContext) =
      extractField[NameDefaultReceptacle[T], T] { nr ⇒ filter(nr.name, fu withDefaultValue nr.default) }
    implicit def forNOUR[T](implicit sfu: SFU, ec: ExecutionContext) =
      extractField[NameOptionUnmarshallerReceptacle[T], Option[T]] { nr ⇒ filter[Option[T]](nr.name, StrictForm.Field.unmarshallerFromFSU(nr.um): FSFFOU[T]) }
    implicit def forNDUR[T](implicit sfu: SFU, ec: ExecutionContext) =
      extractField[NameDefaultUnmarshallerReceptacle[T], T] { nr ⇒ filter(nr.name, (StrictForm.Field.unmarshallerFromFSU(nr.um): FSFFOU[T]) withDefaultValue nr.default) }

    //////////////////// required formField support ////////////////////

    private def requiredFilter[T](fieldName: String, fu: Unmarshaller[Option[StrictForm.Field], T],
                                  requiredValue: Any)(implicit sfu: SFU): Directive0 =
      extract(fieldOfForm(fieldName, fu)).flatMap {
        onComplete(_).flatMap {
          case Success(value) if value == requiredValue ⇒ pass
          case _                                        ⇒ reject
        }
      }
    implicit def forRVR[T](implicit sfu: SFU, fu: FSFFU[T]) =
      fieldDef[RequiredValueReceptacle[T], Directive0] { rvr ⇒ requiredFilter(rvr.name, fu, rvr.requiredValue) }
    implicit def forRVDR[T](implicit sfu: SFU) =
      fieldDef[RequiredValueUnmarshallerReceptacle[T], Directive0] { rvr ⇒ requiredFilter(rvr.name, StrictForm.Field.unmarshallerFromFSU(rvr.um), rvr.requiredValue) }

    //////////////////// tuple support ////////////////////

    import akka.http.server.util.TupleOps._
    import akka.http.server.util.BinaryPolyFunc

    implicit def forTuple[T](implicit fold: FoldLeft[Directive0, T, ConvertParamDefAndConcatenate.type]) =
      fieldDef[T, fold.Out](fold(pass, _))

    object ConvertParamDefAndConcatenate extends BinaryPolyFunc {
      implicit def from[P, TA, TB](implicit fdef: FieldDef[P] { type Out = Directive[TB] }, ev: Join[TA, TB]) =
        at[Directive[TA], P] { (a, t) ⇒ a & fdef(t) }
    }
  }
}