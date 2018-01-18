package hmda.api.http

import akka.NotUsed
import akka.stream.scaladsl.Source
import hmda.api._
import hmda.api.model._
import hmda.census.model.CbsaLookup
import hmda.model.edits.EditMetaDataLookup
import hmda.model.fi.lar.LoanApplicationRegister
import hmda.model.fi.ts.TransmittalSheet
import hmda.model.fi.{ HmdaFileRow, HmdaRowError }
import hmda.model.validation.{ EmptyValidationError, ValidationError }
import hmda.persistence.messages.CommonMessages.Event
import hmda.persistence.messages.events.processing.HmdaFileValidatorEvents._
import spray.json.{ JsNumber, JsObject, JsString, JsValue }

import scala.concurrent.Future

trait ValidationErrorConverter {

  //// New way
  def editStreamOfType[ec: EC, mat: MAT, as: AS](errType: String, editSource: Source[Event, NotUsed]): Source[ValidationError, NotUsed] = {
    val edits: Source[ValidationError, NotUsed] = errType.toLowerCase match {
      case "syntactical" => editSource.map {
        case LarSyntacticalError(err) => err
        case TsSyntacticalError(err) => err
        case _ => EmptyValidationError
      }
      case "validity" => editSource.map {
        case LarValidityError(err) => err
        case TsValidityError(err) => err
        case _ => EmptyValidationError
      }
      case "quality" => editSource.map {
        case LarQualityError(err) => err
        case TsQualityError(err) => err
        case _ => EmptyValidationError
      }
      case "macro" => editSource.map {
        case LarMacroError(err) => err
        case _ => EmptyValidationError
      }
      case "all" => editSource.map {
        case LarSyntacticalError(err) => err
        case TsSyntacticalError(err) => err
        case LarValidityError(err) => err
        case TsValidityError(err) => err
        case LarQualityError(err) => err
        case TsQualityError(err) => err
        case LarMacroError(err) => err
        case _ => EmptyValidationError
      }
    }

    edits.filter(_ != EmptyValidationError)
  }

  private def uniqueEdits[ec: EC, mat: MAT, as: AS](editType: String, editSource: Source[Event, NotUsed]): Future[List[String]] = {
    var uniqueEdits: List[String] = List()
    val runF = editStreamOfType(editType, editSource).runForeach { e =>
      val name = e.ruleName
      if (!uniqueEdits.contains(name)) uniqueEdits = uniqueEdits :+ name
    }
    runF.map(_ => uniqueEdits)
  }

  def editInfosF[ec: EC, mat: MAT, as: AS](editType: String, editSource: Source[Event, NotUsed]): Future[List[EditInfo]] = {
    uniqueEdits(editType, editSource).map { list =>
      val infos = list.map(name => EditInfo(name, editDescription(name)))
      infos.sortBy(_.edit)
    }
  }

  private val csvHeaderSource = Source.fromIterator(() => Iterator("editType, editId, loanId"))

  def csvResultStream[ec: EC, mat: MAT, as: AS](eventSource: Source[Event, NotUsed]): Source[String, Any] = {
    val edits = editStreamOfType("all", eventSource)
    val csvSource = edits.map(_.toCsv)
    csvHeaderSource.concat(csvSource)
  }

  ///// Old way

  def validationErrorToResultRow(err: ValidationError, ts: Option[TransmittalSheet], lars: Seq[LoanApplicationRegister]): EditResultRow = {
    EditResultRow(RowId(err.publicErrorId), relevantFields(err, ts, lars))
  }

  //// Helper methods

  private def editDescription(editName: String): String = {
    EditMetaDataLookup.forEdit(editName).editDescription
  }

  private def relevantFields(err: ValidationError, ts: Option[TransmittalSheet], lars: Seq[LoanApplicationRegister]): JsObject = {
    val fieldNames: Seq[String] = EditMetaDataLookup.forEdit(err.ruleName).fieldNames

    val jsVals: Seq[(String, JsValue)] = fieldNames.map { fieldName =>
      val row = relevantRow(err, ts, lars)
      val fieldValue = if (fieldName == "Metropolitan Statistical Area / Metropolitan Division Name") {
        CbsaLookup.nameFor(row.valueOf("Metropolitan Statistical Area / Metropolitan Division").toString)
      } else {
        row.valueOf(fieldName)
      }
      (fieldName, toJsonVal(fieldValue))
    }

    JsObject(jsVals: _*)
  }

  private def relevantRow(err: ValidationError, ts: Option[TransmittalSheet], lars: Seq[LoanApplicationRegister]): HmdaFileRow = {
    if (err.ts) ts.getOrElse(HmdaRowError())
    else lars.find(lar => lar.loan.id == err.errorId).getOrElse(HmdaRowError())
  }

  private def toJsonVal(value: Any) = {
    value match {
      case i: Int => JsNumber(i)
      case l: Long => JsNumber(l)
      case s: String => JsString(s)
    }
  }

}
