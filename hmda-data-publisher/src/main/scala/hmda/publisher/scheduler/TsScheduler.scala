package hmda.publisher.scheduler

import java.time.{ Clock, Instant, LocalDateTime }
import java.time.format.DateTimeFormatter
import akka.actor.typed.ActorRef
import akka.stream.Materializer
import akka.stream.alpakka.s3.ApiVersion.ListBucketVersion2
import akka.stream.alpakka.s3._
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.scaladsl.{ Sink, Source }
import akka.util.ByteString
import com.typesafe.akka.extension.quartz.QuartzSchedulerExtension
import com.typesafe.config.ConfigFactory
import hmda.actor.HmdaActor
import hmda.publisher.helper.{ PrivateAWSConfigLoader, QuarterTimeBarrier, S3Utils, SnapshotCheck }
import hmda.publisher.qa.{ QAFilePersistor, QAFileSpec, QARepository }
import hmda.publisher.query.component.{ PublisherComponent2018, PublisherComponent2019, PublisherComponent2020 }
import hmda.publisher.scheduler.schedules.Schedule
import hmda.publisher.scheduler.schedules.Schedules.{ TsScheduler2018, TsScheduler2019, TsScheduler2020, TsSchedulerQuarterly2020 }
import hmda.publisher.util.PublishingReporter
import hmda.publisher.util.PublishingReporter.Command.FilePublishingCompleted
import hmda.publisher.validation.PublishingGuard
import hmda.publisher.validation.PublishingGuard.{ Period, Scope }
import hmda.query.DbConfiguration.dbConfig
import hmda.query.ts._
import hmda.util.BankFilterUtils._

import scala.concurrent.Future
import scala.util.{ Failure, Success, Try }

class TsScheduler(publishingReporter: ActorRef[PublishingReporter.Command], qaFilePersistor: QAFilePersistor)
  extends HmdaActor
    with PublisherComponent2018
    with PublisherComponent2019
    with PublisherComponent2020
    with PrivateAWSConfigLoader {

  implicit val ec               = context.system.dispatcher
  implicit val materializer     = Materializer(context)
  private val fullDate          = DateTimeFormatter.ofPattern("yyyy-MM-dd-")
  private val fullDateQuarterly = DateTimeFormatter.ofPattern("yyyy-MM-dd_")

  def tsRepository2018                 = new TransmittalSheetRepository2018(dbConfig)
  def qaTsRepository2018               = createPrivateQaTsRepository2018(dbConfig)
  def tsRepository2019                 = new TransmittalSheetRepository2019(dbConfig)
  def qaTsRepository2019               = createPrivateQaTsRepository2019(dbConfig)
  def tsRepository2020                 = createTransmittalSheetRepository2020(dbConfig, Year2020Period.Whole)
  def tsRepository2020Q1               = createTransmittalSheetRepository2020(dbConfig, Year2020Period.Q1)
  def tsRepository2020Q2               = createTransmittalSheetRepository2020(dbConfig, Year2020Period.Q2)
  def tsRepository2020Q3               = createTransmittalSheetRepository2020(dbConfig, Year2020Period.Q3)
  def qaTsRepository2020               = createQaTransmittalSheetRepository2020(dbConfig, Year2020Period.Whole)
  def qaTsRepository2020Q1             = createQaTransmittalSheetRepository2020(dbConfig, Year2020Period.Q1)
  def qaTsRepository2020Q2             = createQaTransmittalSheetRepository2020(dbConfig, Year2020Period.Q2)
  def qaTsRepository2020Q3             = createQaTransmittalSheetRepository2020(dbConfig, Year2020Period.Q3)
  val publishingGuard: PublishingGuard = PublishingGuard.create(this)(context.system)
  val timeBarrier: QuarterTimeBarrier  = new QuarterTimeBarrier(Clock.systemDefaultZone())

  val awsConfig =
    ConfigFactory.load("application.conf").getConfig("private-aws")

  val s3Settings = S3Settings(context.system)
    .withBufferType(MemoryBufferType)
    .withCredentialsProvider(awsCredentialsProviderPrivate)
    .withS3RegionProvider(awsRegionProviderPrivate)
    .withListBucketApiVersion(ListBucketVersion2)

  override def preStart(): Unit = {
    QuartzSchedulerExtension(context.system)
      .schedule("TsScheduler2018", self, TsScheduler2018)
    QuartzSchedulerExtension(context.system)
      .schedule("TsScheduler2019", self, TsScheduler2019)
    QuartzSchedulerExtension(context.system)
      .schedule("TsScheduler2020", self, TsScheduler2020)
    QuartzSchedulerExtension(context.system)
      .schedule("TsSchedulerQuarterly2020", self, TsSchedulerQuarterly2020)

  }

  override def postStop(): Unit = {
    QuartzSchedulerExtension(context.system).cancelJob("TsScheduler2018")
    QuartzSchedulerExtension(context.system).cancelJob("TsScheduler2019")
    QuartzSchedulerExtension(context.system).cancelJob("TsScheduler2020")
    QuartzSchedulerExtension(context.system).cancelJob("TsSchedulerQuarterly2020")
  }

  private def uploadFileToS3(
                              s3Sink: Sink[ByteString, Future[MultipartUploadResult]],
                              transmittalSheets: => Future[Seq[TransmittalSheetEntity]]
                            ): Future[MultipartUploadResult] = {
    val source = Source
      .future(transmittalSheets)
      .mapConcat(_.toList)
      .map(transmittalSheet => transmittalSheet.toRegulatorPSV + "\n")
      .map(ByteString(_))
    S3Utils.uploadWithRetry(source, s3Sink)
  }

  override def receive: Receive = {

    case schedule @ TsScheduler2018 =>
      publishingGuard.runIfDataIsValid(Period.y2018, Scope.Private) {
        val now           = LocalDateTime.now().minusDays(1)
        val formattedDate = fullDate.format(now)
        val fileName      = s"$formattedDate" + "2018_ts.txt"
        val s3Path        = s"$environmentPrivate/ts/"
        val fullFilePath  = SnapshotCheck.pathSelector(s3Path, fileName)

        val s3Sink =
          S3.multipartUpload(bucketPrivate, fullFilePath)
            .withAttributes(S3Attributes.settings(s3Settings))

        val results: Future[MultipartUploadResult] =
          uploadFileToS3(s3Sink, tsRepository2018.getAllSheets(getFilterList()))

        results.foreach(_ => persistFileForQa(fullFilePath, qaTsRepository2018))
        results.onComplete(reportPublishingResult(_, schedule, fullFilePath))
      }

    case schedule @ TsScheduler2019 =>
      publishingGuard.runIfDataIsValid(Period.y2019, Scope.Private) {
        val now           = LocalDateTime.now().minusDays(1)
        val formattedDate = fullDate.format(now)
        val fileName      = s"$formattedDate" + "2019_ts.txt"
        val s3Path        = s"$environmentPrivate/ts/"
        val fullFilePath  = SnapshotCheck.pathSelector(s3Path, fileName)

        val s3Sink =
          S3.multipartUpload(bucketPrivate, fullFilePath)
            .withAttributes(S3Attributes.settings(s3Settings))

        val results: Future[MultipartUploadResult] =
          uploadFileToS3(s3Sink, tsRepository2019.getAllSheets(getFilterList()))

        results.foreach(_ => persistFileForQa(fullFilePath, qaTsRepository2019))
        results.onComplete(reportPublishingResult(_, schedule, fullFilePath))
      }

    case schedule @ TsScheduler2020 =>
      publishingGuard.runIfDataIsValid(Period.y2020, Scope.Private) {
        val now           = LocalDateTime.now().minusDays(1)
        val formattedDate = fullDate.format(now)
        val fileName      = s"$formattedDate" + "2020_ts.txt"
        val s3Path        = s"$environmentPrivate/ts/"
        val fullFilePath  = SnapshotCheck.pathSelector(s3Path, fileName)

        val s3Sink =
          S3.multipartUpload(bucketPrivate, fullFilePath)
            .withAttributes(S3Attributes.settings(s3Settings))

        val results: Future[MultipartUploadResult] =
          uploadFileToS3(s3Sink, tsRepository2020.getAllSheets(getFilterList()))

        results.foreach(_ => persistFileForQa(fullFilePath, qaTsRepository2020))
        results.onComplete(reportPublishingResult(_, schedule, fullFilePath))
      }
    case schedule @ TsSchedulerQuarterly2020 =>
      val now           = LocalDateTime.now().minusDays(1)
      val formattedDate = fullDateQuarterly.format(now)
      val s3Path        = s"$environmentPrivate/ts/"
      def publishQuarter[Table <: RealTransmittalSheetTable](
                                                              quarter: Period.Quarter,
                                                              repo: TSRepository2020Base[Table],
                                                              fileNameSuffix: String,
                                                              qaRepository: QARepository[TransmittalSheetEntity]
                                                            ) =
        timeBarrier.runIfStillRelevant(quarter) {
          publishingGuard.runIfDataIsValid(quarter, Scope.Private) {
            val fileName     = formattedDate + fileNameSuffix
            val fullFilePath = SnapshotCheck.pathSelector(s3Path, fileName)
            val s3Sink =
              S3.multipartUpload(bucketPrivate, fullFilePath)
                .withAttributes(S3Attributes.settings(s3Settings))

            def data: Future[Seq[TransmittalSheetEntity]] =
              repo.getAllSheets(getFilterList())

            val results: Future[MultipartUploadResult] =
              uploadFileToS3(s3Sink, data)

            results.foreach(_ => persistFileForQa(fullFilePath, qaRepository))
            results.onComplete(reportPublishingResult(_, schedule, fullFilePath))

          }
        }

      publishQuarter(Period.y2020Q1, tsRepository2020Q1, "quarter_1_2020_ts.txt", qaTsRepository2020Q1)
      publishQuarter(Period.y2020Q2, tsRepository2020Q2, "quarter_2_2020_ts.txt", qaTsRepository2020Q2)
      publishQuarter(Period.y2020Q3, tsRepository2020Q3, "quarter_3_2020_ts.txt", qaTsRepository2020Q3)

  }

  private def persistFileForQa(s3ObjKey: String, repository: QARepository[TransmittalSheetEntity]) = {
    val spec = QAFileSpec(
      bucket = bucketPrivate,
      key = s3ObjKey,
      s3Settings = s3Settings,
      withHeaderLine = false,
      parseLine = TransmittalSheetEntity.RegulatorParser.parseFromPSVUnsafe,
      repository = repository
    )
    qaFilePersistor.fetchAndPersist(spec)
  }

  def reportPublishingResult(result: Try[Any], schedule: Schedule, fullFilePath: String): Unit =
    result match {
      case Success(result) =>
        publishingReporter ! FilePublishingCompleted(
          schedule,
          fullFilePath,
          None,
          Instant.now,
          FilePublishingCompleted.Status.Success
        )
        log.info(s"Pushed to S3: $bucketPrivate/$fullFilePath.")
      case Failure(t) =>
        publishingReporter ! FilePublishingCompleted(
          schedule,
          fullFilePath,
          None,
          Instant.now,
          FilePublishingCompleted.Status.Error(t.getMessage)
        )
        log.error(s"An error has occurred while publishing $bucketPrivate/$fullFilePath: " + t.getMessage, t)
    }

}