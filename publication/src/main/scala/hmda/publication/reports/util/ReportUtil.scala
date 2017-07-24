package hmda.publication.reports.util

import akka.NotUsed
import akka.stream.scaladsl.Source
import hmda.census.model._
import hmda.model.publication.reports._
import hmda.model.publication.reports.RaceEnum._
import hmda.publication.reports.{ AS, EC, MAT }
import hmda.publication.reports.util.DispositionTypes._
import hmda.query.model.filing.LoanApplicationRegisterQuery
import hmda.util.SourceUtils

import scala.concurrent.Future

object ReportUtil extends SourceUtils {

  def msaReport(fipsCode: String): MSAReport = {
    val cbsa = CbsaLookup.values.find(x => x.cbsa == fipsCode).getOrElse(Cbsa())
    val stateFips = cbsa.key.substring(0, 2)
    val state = StateAbrvLookup.values.find(s => s.state == stateFips).getOrElse(StateAbrv("", "", ""))
    MSAReport(fipsCode, CbsaLookup.nameFor(fipsCode), state.stateAbrv, state.stateName)
  }

  def calculateMedianIncomeIntervals(fipsCode: Int): Array[Double] = {
    val msaIncome = MsaIncomeLookup.values.find(msa => msa.fips == fipsCode).getOrElse(MsaIncome())
    val medianIncome = msaIncome.income / 1000
    val i50 = medianIncome * 0.5
    val i80 = medianIncome * 0.8
    val i100 = medianIncome
    val i120 = medianIncome * 1.2
    Array(i50, i80, i100, i120)
  }

  def calculateDate[ec: EC, mat: MAT, as: AS](larSource: Source[LoanApplicationRegisterQuery, NotUsed]): Future[Int] = {
    collectHeadValue(larSource).map(lar => lar.actionTakenDate.toString.substring(0, 4).toInt)
  }

  def calculateDispositions[ec: EC, mat: MAT, as: AS](larSource: Source[LoanApplicationRegisterQuery, NotUsed], dispositions: List[DispositionType]): Future[List[Disposition]] = {
    Future.sequence(dispositions.map(_.calculateDisposition(larSource)))
  }

  def filterRace(larSource: Source[LoanApplicationRegisterQuery, NotUsed], race: RaceEnum): Source[LoanApplicationRegisterQuery, NotUsed] = {
    race match {
      case AmericanIndianOrAlaskaNative =>
        larSource.filter { lar =>
          (lar.race1 == 1 && applicantNonWhite(lar) && coApplicantNonWhite(lar)) ||
            (lar.race1 == 1 && lar.race2 == "5" && coApplicantNonWhite(lar))
        }

      case Asian =>
        larSource.filter { lar =>
          (lar.race1 == 2 && applicantNonWhite(lar) && coApplicantNonWhite(lar)) ||
            (lar.race1 == 2 && lar.race2 == "5" && coApplicantNonWhite(lar))
        }

      case BlackOrAfricanAmerican =>
        larSource.filter { lar =>
          (lar.race1 == 3 && applicantNonWhite(lar) && coApplicantNonWhite(lar)) ||
            (lar.race1 == 3 && lar.race2 == "5" && coApplicantNonWhite(lar))
        }

      case HawaiianOrPacific =>
        larSource.filter { lar =>
          (lar.race1 == 4 && applicantNonWhite(lar) && coApplicantNonWhite(lar)) ||
            (lar.race1 == 4 && lar.race2 == "5" && coApplicantNonWhite(lar))
        }

      case White =>
        larSource.filter(lar => lar.race1 == 5 && coApplicantNonWhite(lar))

      case TwoOrMoreMinority =>
        larSource.filter(lar => applicantTwoOrMoreMinorities(lar) && coApplicantNonWhite(lar))

      case Joint =>
        larSource.filter { lar =>
          (applicantTwoOrMoreMinorities(lar) || coApplicantTwoOrMoreMinorities(lar)) &&
            (applicantWhite(lar) || coApplicantWhite(lar))
        }

      case NotProvided =>
        larSource.filter(lar => lar.race1 == 6 || lar.race1 == 7)

    }
  }

  private def applicantWhite(lar: LoanApplicationRegisterQuery): Boolean = {
    lar.race1 == 5 &&
      lar.race2 == "" &&
      lar.race3 == "" &&
      lar.race4 == "" &&
      lar.race5 == ""
  }

  private def applicantNonWhite(lar: LoanApplicationRegisterQuery): Boolean = {
    lar.race1 != 5 &&
      lar.race2 == "" &&
      lar.race3 == "" &&
      lar.race4 == "" &&
      lar.race5 == ""
  }

  private def coApplicantWhite(lar: LoanApplicationRegisterQuery): Boolean = {
    lar.coRace1 == 5 &&
      lar.coRace2 != "5" &&
      lar.coRace3 != "5" &&
      lar.coRace4 != "5" &&
      lar.coRace5 != "5"
  }

  private def coApplicantNonWhite(lar: LoanApplicationRegisterQuery): Boolean = {
    lar.coRace1 != 5 &&
      lar.coRace2 != "5" &&
      lar.coRace3 != "5" &&
      lar.coRace4 != "5" &&
      lar.coRace5 != "5"
  }

  private def applicantTwoOrMoreMinorities(lar: LoanApplicationRegisterQuery): Boolean = {
    lar.race1 != 5 &&
      ((lar.race2 != "" && lar.race2 != "5") ||
        (lar.race3 != "" && lar.race3 != "5") ||
        (lar.race4 != "" && lar.race4 != "5") ||
        (lar.race5 != "" && lar.race5 != "5"))
  }

  private def coApplicantTwoOrMoreMinorities(lar: LoanApplicationRegisterQuery): Boolean = {
    lar.coRace1 != 5 &&
      ((lar.coRace2 != "" && lar.coRace2 != "5") ||
        (lar.coRace3 != "" && lar.coRace3 != "5") ||
        (lar.coRace4 != "" && lar.coRace4 != "5") ||
        (lar.coRace5 != "" && lar.coRace5 != "5"))
  }

  def raceBorrowerCharacteristic[as: AS, mat: MAT, ec: EC](larSource: Source[LoanApplicationRegisterQuery, NotUsed], applicantIncomeEnum: ApplicantIncomeEnum, dispositions: List[DispositionType]): Future[List[RaceCharacteristic]] = {

    val larsAlaskan = filterRace(larSource, AmericanIndianOrAlaskaNative)
    val larsAsian = filterRace(larSource, Asian)
    val larsBlack = filterRace(larSource, BlackOrAfricanAmerican)
    val larsHawaiian = filterRace(larSource, HawaiianOrPacific)
    val larsWhite = filterRace(larSource, White)
    val larsTwoMinorities = filterRace(larSource, TwoOrMoreMinority)
    val larsJoint = filterRace(larSource, Joint)
    val larsNotProvided = filterRace(larSource, NotProvided)

    val dispAlaskanF = calculateDispositions(larsAlaskan, dispositions)
    val dispAsianF = calculateDispositions(larsAsian, dispositions)
    val dispBlackF = calculateDispositions(larsBlack, dispositions)
    val dispHawaiianF = calculateDispositions(larsHawaiian, dispositions)
    val dispWhiteF = calculateDispositions(larsWhite, dispositions)
    val dispTwoMinoritiesF = calculateDispositions(larsTwoMinorities, dispositions)
    val dispJointF = calculateDispositions(larsJoint, dispositions)
    val dispNotProvidedF = calculateDispositions(larsNotProvided, dispositions)

    for {
      dispAlaskan <- dispAlaskanF
      dispAsian <- dispAsianF
      dispBlack <- dispBlackF
      dispHawaiian <- dispHawaiianF
      dispWhite <- dispWhiteF
      dispTwoMinorities <- dispTwoMinoritiesF
      dispJoint <- dispJointF
      dispNotProvided <- dispNotProvidedF
    } yield {
      val alaskanCharacteristic = RaceCharacteristic(AmericanIndianOrAlaskaNative, dispAlaskan)
      val asianCharacteristic = RaceCharacteristic(Asian, dispAsian)
      val blackCharacteristic = RaceCharacteristic(BlackOrAfricanAmerican, dispBlack)
      val hawaiianCharacteristic = RaceCharacteristic(HawaiianOrPacific, dispHawaiian)
      val whiteCharacteristic = RaceCharacteristic(White, dispWhite)
      val twoOrMoreMinorityCharacteristic = RaceCharacteristic(TwoOrMoreMinority, dispTwoMinorities)
      val jointCharacteristic = RaceCharacteristic(Joint, dispJoint)
      val notProvidedCharacteristic = RaceCharacteristic(NotProvided, dispNotProvided)

      List(
        alaskanCharacteristic,
        asianCharacteristic,
        blackCharacteristic,
        hawaiianCharacteristic,
        whiteCharacteristic,
        twoOrMoreMinorityCharacteristic,
        jointCharacteristic,
        notProvidedCharacteristic
      )
    }

  }

}
