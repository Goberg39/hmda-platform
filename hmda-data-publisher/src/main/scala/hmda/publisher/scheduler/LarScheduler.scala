package hmda.publisher.scheduler

import akka.NotUsed
import akka.actor.typed.ActorRef
import akka.stream.Materializer
import akka.stream.alpakka.s3.ApiVersion.ListBucketVersion2
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.alpakka.s3.{MemoryBufferType, MetaHeaders, S3Attributes, S3Settings}
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.typesafe.akka.extension.quartz.QuartzSchedulerExtension
import hmda.actor.HmdaActor
import hmda.census.records.CensusRecords
import hmda.model.census.Census
import hmda.model.publication.Msa
import hmda.publisher.helper._
import hmda.publisher.qa.{QAFilePersistor, QAFileSpec, QARepository}
import hmda.publisher.query.component.{PublisherComponent2018, PublisherComponent2019, PublisherComponent2020}
import hmda.publisher.query.lar.{LarEntityImpl2018, LarEntityImpl2019, LarEntityImpl2019WithMsa, LarEntityImpl2020, LarEntityImpl2020WithMsa}
import hmda.publisher.scheduler.schedules.Schedule
import hmda.publisher.scheduler.schedules.Schedules._
import hmda.publisher.util.PublishingReporter
import hmda.publisher.util.PublishingReporter.Command.FilePublishingCompleted
import hmda.publisher.validation.PublishingGuard
import hmda.publisher.validation.PublishingGuard.{Period, Scope}
import hmda.query.DbConfiguration.dbConfig
import hmda.util.BankFilterUtils._
import java.time.format.DateTimeFormatter
import java.time.{Clock, Instant, LocalDateTime}

import scala.concurrent.Future
import scala.util.{Failure, Success}

class LarScheduler(publishingReporter: ActorRef[PublishingReporter.Command], qaFilePersistor: QAFilePersistor)
  extends HmdaActor
    with PublisherComponent2018
    with PublisherComponent2019
    with PublisherComponent2020
    with LoanLimitLarHeader
    with PrivateAWSConfigLoader {

  implicit val ec               = context.system.dispatcher
  implicit val materializer     = Materializer(context)
  private val fullDate          = DateTimeFormatter.ofPattern("yyyy-MM-dd-")
  private val fullDateQuarterly = DateTimeFormatter.ofPattern("yyyy-MM-dd_")

  def larRepository2018                = new LarRepository2018(dbConfig)
  def qaLarRepository2018              = new QALarRepository2018(dbConfig)
  def larRepository2019                = new LarRepository2019(dbConfig)
  def qaLarRepository2019              = new QALarRepository2019(dbConfig)
  def qaLarRepository2019LoanLimit     = new QALarRepository2019LoanLimit(dbConfig)
  def qaLarRepository2020LoanLimit     = new QALarRepository2020LoanLimit(dbConfig)

  def larRepository2020                = createLarRepository2020(dbConfig, Year2020Period.Whole)
  def larRepository2020Q1              = createLarRepository2020(dbConfig, Year2020Period.Q1)
  def larRepository2020Q2              = createLarRepository2020(dbConfig, Year2020Period.Q2)
  def larRepository2020Q3              = createLarRepository2020(dbConfig, Year2020Period.Q3)

  def qaLarRepository2020              = createQaLarRepository2020(dbConfig, Year2020Period.Whole)
  def qaLarRepository2020Q1            = createQaLarRepository2020(dbConfig, Year2020Period.Q1)
  def qaLarRepository2020Q2            = createQaLarRepository2020(dbConfig, Year2020Period.Q2)
  def qaLarRepository2020Q3            = createQaLarRepository2020(dbConfig, Year2020Period.Q3)
  val publishingGuard: PublishingGuard = PublishingGuard.create(this)(context.system)
  val timeBarrier: QuarterTimeBarrier  = new QuarterTimeBarrier(Clock.systemDefaultZone())

  val indexTractMap2018: Map[String, Census] = CensusRecords.indexedTract2018
  val indexTractMap2019: Map[String, Census] = CensusRecords.indexedTract2019
  val indexTractMap2020: Map[String, Census] = CensusRecords.indexedTract2020

  val s3Settings = S3Settings(context.system)
    .withBufferType(MemoryBufferType)
    .withCredentialsProvider(awsCredentialsProviderPrivate)
    .withS3RegionProvider(awsRegionProviderPrivate)
    .withListBucketApiVersion(ListBucketVersion2)

  override def preStart() = {
    QuartzSchedulerExtension(context.system)
      .schedule("LarScheduler2018", self, LarScheduler2018)
    QuartzSchedulerExtension(context.system)
      .schedule("LarScheduler2019", self, LarScheduler2019)
    QuartzSchedulerExtension(context.system)
    QuartzSchedulerExtension(context.system)
      .schedule("LarScheduler2020", self, LarScheduler2020)
    QuartzSchedulerExtension(context.system)
      .schedule("LarSchedulerLoanLimit2019", self, LarSchedulerLoanLimit2019)
    QuartzSchedulerExtension(context.system)
      .schedule("LarSchedulerQuarterly2020", self, LarSchedulerQuarterly2020)
  }

  override def postStop() = {
    QuartzSchedulerExtension(context.system).cancelJob("LarScheduler2018")
    QuartzSchedulerExtension(context.system).cancelJob("LarScheduler2019")
    QuartzSchedulerExtension(context.system).cancelJob("LarScheduler2020")
    QuartzSchedulerExtension(context.system).cancelJob("LarSchedulerLoanLimit2019")
    QuartzSchedulerExtension(context.system).cancelJob("LarSchedulerQuarterly2020")
  }

  override def receive: Receive = {

    case schedule @ LarScheduler2018 =>
      publishingGuard.runIfDataIsValid(Period.y2018, Scope.Private) {
        val now           = LocalDateTime.now().minusDays(1)
        val formattedDate = fullDate.format(now)
        val fileName      = s"$formattedDate" + "2018_lar.txt"

        val allResultsSource: Source[String, NotUsed] =
          Source
            .fromPublisher(larRepository2018.getAllLARs(getFilterList()))
            .map(larEntity => larEntity.toRegulatorPSV)

        def countF: Future[Int] = larRepository2018.getAllLARsCount(getFilterList())

        for {
          s3ObjName <- publishPSVtoS3(fileName, allResultsSource, countF, schedule)
          _         <- persistFileForQa(s3ObjName, LarEntityImpl2018.parseFromPSVUnsafe, qaLarRepository2018)
        } yield ()
      }

    case schedule @ LarScheduler2019 =>
      publishingGuard.runIfDataIsValid(Period.y2019, Scope.Private) {
        val now           = LocalDateTime.now().minusDays(1)
        val formattedDate = fullDate.format(now)
        val fileName      = s"$formattedDate" + "2019_lar.txt"
        val allResultsSource: Source[String, NotUsed] =
          Source
            .fromPublisher(larRepository2019.getAllLARs(getFilterList()))
            .map(larEntity => larEntity.toRegulatorPSV)

        def countF: Future[Int] = larRepository2019.getAllLARsCount(getFilterList())

        for {
          s3ObjName <- publishPSVtoS3(fileName, allResultsSource, countF, schedule)
          _         <- persistFileForQa(s3ObjName, LarEntityImpl2019.parseFromPSVUnsafe, qaLarRepository2019)
        } yield ()
      }

    case schedule @ LarScheduler2020 =>
      publishingGuard.runIfDataIsValid(Period.y2020, Scope.Private) {
        val now           = LocalDateTime.now().minusDays(1)
        val formattedDate = fullDate.format(now)
        val fileName      = s"$formattedDate" + "2020_lar.txt"
        val allResultsSource: Source[String, NotUsed] =
          Source
            .fromPublisher(larRepository2020.getAllLARs(getFilterList()))
            .map(larEntity => larEntity.toRegulatorPSV)

        def countF: Future[Int] = larRepository2020.getAllLARsCount(getFilterList())

        for {
          s3ObjName <- publishPSVtoS3(fileName, allResultsSource, countF, schedule)
          _         <- persistFileForQa(s3ObjName, LarEntityImpl2020.parseFromPSVUnsafe, qaLarRepository2020)
        } yield ()
      }

    case schedule @ LarSchedulerLoanLimit2019 =>
      publishingGuard.runIfDataIsValid(Period.y2019, Scope.Private) {
        val now           = LocalDateTime.now().minusDays(1)
        val formattedDate = fullDate.format(now)
        val fileName      = "2019F_AGY_LAR_withFlag_" + s"$formattedDate" + "2019_lar.txt"
        val allResultsSource: Source[String, NotUsed] =
          Source
            .fromPublisher(larRepository2019.getAllLARs(getFilterList()))
            .map(larEntity => appendCensus2019(larEntity, 2019))
            .prepend(Source(List(LoanLimitHeader)))

        def countF: Future[Int] = larRepository2019.getAllLARsCount(getFilterList())

        for {
          s3ObjName <- publishPSVtoS3(fileName, allResultsSource, countF, schedule)
          _         <- persistFileForQa(s3ObjName, LarEntityImpl2019WithMsa.parseFromPSVUnsafe, qaLarRepository2019LoanLimit)
        } yield ()
      }

    case schedule @ LarSchedulerLoanLimit2020 =>
      publishingGuard.runIfDataIsValid(Period.y2020, Scope.Private) {
        val now           = LocalDateTime.now().minusDays(1)
        val formattedDate = fullDate.format(now)
        val fileName      = "2020F_AGY_LAR_withFlag_" + s"$formattedDate" + "2020_lar.txt"
        val allResultsSource: Source[String, NotUsed] =
          Source
            .fromPublisher(larRepository2020.getAllLARs(getFilterList()))
            .map(larEntity => appendCensus2020(larEntity, 2020))
            .prepend(Source(List(LoanLimitHeader)))

        def countF: Future[Int] = larRepository2020.getAllLARsCount(getFilterList())

        for {
          s3ObjName <- publishPSVtoS3(fileName, allResultsSource, countF, schedule)
          _         <- persistFileForQa(s3ObjName, LarEntityImpl2020WithMsa.parseFromPSVUnsafe, qaLarRepository2020LoanLimit)
        } yield ()
      }
    case schedule @ LarSchedulerQuarterly2020 =>
      val includeQuarterly = true
      val now              = LocalDateTime.now().minusDays(1)
      val formattedDate    = fullDateQuarterly.format(now)

      def publishQuarter[Table <: RealLarTable](
                                                 quarter: Period.Quarter,
                                                 fileNameSuffix: String,
                                                 repo: LarRepository2020Base[Table],
                                                 qaRepository: QARepository[LarEntityImpl2020]
                                               ) =
        timeBarrier.runIfStillRelevant(quarter) {
          publishingGuard.runIfDataIsValid(quarter, Scope.Private) {
            val fileName = formattedDate + fileNameSuffix

            val allResultsSource: Source[String, NotUsed] = Source
              .fromPublisher(repo.getAllLARs(getFilterList()))
              .map(larEntity => larEntity.toRegulatorPSV)

            def countF: Future[Int] = repo.getAllLARsCount(getFilterList())

            publishPSVtoS3(fileName, allResultsSource, countF, schedule)
            for {
              s3ObjName <- publishPSVtoS3(fileName, allResultsSource, countF, schedule)
              _         <- persistFileForQa(s3ObjName, LarEntityImpl2020.parseFromPSVUnsafe, qaRepository)
            } yield ()
          }
        }
      publishQuarter(Period.y2020Q1, "quarter_1_2020_lar.txt", larRepository2020Q1, qaLarRepository2020Q1)
      publishQuarter(Period.y2020Q2, "quarter_2_2020_lar.txt", larRepository2020Q2, qaLarRepository2020Q2)
      publishQuarter(Period.y2020Q3, "quarter_3_2020_lar.txt", larRepository2020Q3, qaLarRepository2020Q3)
  }

  // returns effective file name/s3 object key
  def publishPSVtoS3(fileName: String, rows: Source[String, NotUsed], countF: => Future[Int], schedule: Schedule): Future[String] = {
    val s3Path       = s"$environmentPrivate/lar/"
    val fullFilePath = SnapshotCheck.pathSelector(s3Path, fileName)

    val bytesStream: Source[ByteString, NotUsed] =
      rows
        .map(_ + "\n")
        .map(s => ByteString(s))

    val results = for {
      count <- countF
      s3Sink = S3
        .multipartUpload(bucketPrivate, fullFilePath, metaHeaders = MetaHeaders(Map(LarScheduler.entriesCountMetaName -> count.toString)))
        .withAttributes(S3Attributes.settings(s3Settings))
      _ <- S3Utils.uploadWithRetry(bytesStream, s3Sink)
    } yield count

    def sendPublishingNotif(error: Option[String], count: Option[Int]): Unit = {
      val status = error match {
        case Some(value) => FilePublishingCompleted.Status.Error(value)
        case None        => FilePublishingCompleted.Status.Success
      }
      publishingReporter ! FilePublishingCompleted(schedule, fullFilePath, count, Instant.now(), status)
    }

    results onComplete {
      case Success(count) =>
        sendPublishingNotif(None, Some(count))
        log.info(s"Pushed to S3: $bucketPrivate/$fullFilePath.")
      case Failure(t) =>
        sendPublishingNotif(Some(t.getMessage), None)
        log.info(s"An error has occurred pushing LAR Data to $bucketPrivate/$fullFilePath: ${t.getMessage}")
    }

    results.map(_ => fullFilePath)
  }

  def getCensus(hmdaGeoTract: String, year: Int): Msa = {

    val indexTractMap = year match {
      case 2018 => indexTractMap2018
      case 2019 => indexTractMap2019
      case 2020 => indexTractMap2020
      case _    => indexTractMap2020
    }
    val censusResult = indexTractMap.getOrElse(hmdaGeoTract, Census())
    val censusID =
      if (censusResult.msaMd == 0) "-----" else censusResult.msaMd.toString
    val censusName =
      if (censusResult.name.isEmpty) "MSA/MD NOT AVAILABLE" else censusResult.name
    Msa(censusID, censusName)
  }

  def appendCensus2019(lar: LarEntityImpl2019, year: Int): String = {
    val msa = getCensus(lar.larPartOne.tract, year)
    lar.appendMsa(msa).toRegulatorPSV
  }

  def appendCensus2020(lar: LarEntityImpl2020, year: Int): String = {
    val msa = getCensus(lar.larPartOne.tract, year)
    lar.appendMsa(msa).toRegulatorPSV
  }

  private def persistFileForQa[T](s3ObjKey: String, parseLine: String => T, repository: QARepository[T]) = {
    val spec = QAFileSpec(
      bucket = bucketPrivate,
      key = s3ObjKey,
      s3Settings = s3Settings,
      withHeaderLine = true,
      parseLine = parseLine,
      repository = repository
    )
    qaFilePersistor.fetchAndPersist(spec)
  }
}

object LarScheduler {
  val entriesCountMetaName = "entries-count"
}