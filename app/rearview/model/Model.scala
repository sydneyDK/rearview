/**
 * Definitions of model used throughout the application.  The ModelImplicits are mappings for JSON
 * transformation using the Play JSON api.
 */
package rearview.model

import java.util.Date

import play.api.libs.functional.syntax._
import play.api.libs.json.Format._
import play.api.libs.json.Reads._
import play.api.libs.json.Writes._
import play.api.libs.json._
import rearview.monitor.Monitor

class JobParseException(message: String) extends Exception(message)

/**
 * Basically a container for jobs with no real behavior
 * @param id application id
 * @param userId user id who created the application
 * @param name name of the application
 */
case class Application(id:         Option[Long],
                       userId:     Long,
                       name:       String,
                       createdAt:  Option[Date] = None,
                       modifiedAt: Option[Date] = None)

/**
 * User object which is dynamically created from a successful openid auth sequence.
 * @param id
 * @param email
 * @param firstName
 * @param lastName
 * @param lastLogin
 */
case class User(id:         Option[Long],
                email:      String,
                firstName:  String,
                lastName:   String,
                lastLogin:  Option[Date] = None,
                createdAt:  Option[Date] = None,
                modifiedAt: Option[Date] = None)

/**
 * JobStatus indicates the result of the last job run. Each job will have a status after has run at least once.
 */
sealed abstract class JobStatus(val name: String) extends Serializable
case object SuccessStatus extends JobStatus("success")
case object FailedStatus extends JobStatus("failed")
case object ErrorStatus extends JobStatus("error")
case object GraphiteErrorStatus extends JobStatus("graphite_error")
case object GraphiteMetricErrorStatus extends JobStatus("graphite_metric_error")
case object SecurityErrorStatus extends JobStatus("security_error")

object JobStatus {
  def unapply(s: String) = s match {
    case SuccessStatus.name             => Some(SuccessStatus)
    case FailedStatus.name              => Some(FailedStatus)
    case ErrorStatus.name               => Some(ErrorStatus)
    case GraphiteErrorStatus.name       => Some(GraphiteErrorStatus)
    case GraphiteMetricErrorStatus.name => Some(GraphiteMetricErrorStatus)
    case SecurityErrorStatus.name       => Some(SecurityErrorStatus)
    case _                              => None
  }
}


/**
 * Represents a job's metadata.
 * @param id the job's id which is defined upon insert
 * @param userId the id of the user who created the job
 * @param appId the application id the job belongs to
 * @param name the name of this job
 * @param cronExpr the cron expression used for scheduling
 * @param metrics one or more graphite metrics
 * @param monitorExpr the ruby expression to evaluate on the data fetched from the metrics
 * @param minutes number of minutes back to retrieve data
 * @param toDate optional end data to get <i>minutes</i> of data from
 * @param description option longer description of the job's intended purpose
 * @param active indicates whether the job is active (i.e. gets scheduled)
 * @param status the result of the last run
 * @param lastRun timestamp of the last run
 * @param nextRun timestamp of the next run (not persisted, used by scheduler)
 * @param alertKeys options list of pager duty keys or email addresses
 * @param errorTimeout an optional interval to during alerting
 * @param createdAt timestamp of record creation
 * @param modifiedAt timestamp of record modification
 */
case class Job(id:            Option[Long],
               userId:        Long,
               appId:         Long,
               name:          String,
               cronExpr:      String,
               metrics:       List[String],
               monitorExpr:   Option[String] = None,
               minutes:       Option[Int] = None,
               toDate:        Option[String] = None,
               description:   Option[String] = None,
               active:        Boolean = true,
               status:        Option[JobStatus] = None,
               lastRun:       Option[Date] = None,
               nextRun:       Option[Date] = None,
               alertKeys:     Option[List[AlertKey]] = None,
               errorTimeout:  Int = Constants.ERROR_TIMEOUT,
               createdAt:     Option[Date] = None,
               modifiedAt:    Option[Date] = None,
               deletedAt:     Option[Date] = None)



sealed trait AlertKey {
  val label: String
  val value: String
}
case class EmailAlertKey(label: String, value: String) extends AlertKey
case class PagerDutyAlertKey(label: String, value: String) extends AlertKey
case class VictorOpsAlertKey(label: String, value: String) extends AlertKey


/**
 * Class with properties from a job error.
 * @param id id of record
 * @param jobId job id error is bound to
 * @param date date the error occurred
 * @param message any message that was generated by the monitor (or error)
 */
case class JobError(id: Long, jobId: Long, date: Date, status: JobStatus, message: Option[String], endDate: Option[Date] = None)


/**
 * Class with data from the last job run.  Only one copy is ever stored in the database.
 * @param status result of the job run (success, failure, etc - JobType)
 * @param output any text output formt he monitor
 * @param graphData the graph data associated with the monitor
 */
case class MonitorOutput(status: JobStatus, output: String, graphData: JsValue = JsObject(Nil))


/**
 * Represents various properties from evaluating a monitor.
 * @param status JobStatus from the run
 * @param output any output from the monitor
 * @param message optional failure message
 * @param data the data from the monitor run
 */
case class AnalysisResult(status: JobStatus, output: MonitorOutput, message: Option[String], data: ModelImplicits.TimeSeries = Nil)


/**
 * Each graphite request returns a 2-dim sequence of datapoints.
 * @param metric the metric for this timeseries
 * @param timestamp timestamp (as epoch) for the datapoint
 * @param value the value of the datapoint
 */
case class DataPoint(metric: String, timestamp: Long, value: Option[Double]) {
  override def toString = "(" + metric + "," + timestamp + "," + value.map(_.toString).getOrElse("") + ")"
}


/**
 * A bunch of implicits used by JSON api, etc.
 */
object ModelImplicits {
  type TimeSeries = Seq[Seq[DataPoint]]


  implicit val applicationFormat: Format[Application] = (
      (__ \ "id").formatNullable[Long] ~
      (__ \ "userId").format[Long] ~
      (__ \ "name").format[String] ~
      (__ \ "createdAt").formatNullable[Date] ~
      (__ \ "modifiedAt").formatNullable[Date])(Application.apply, unlift(Application.unapply))


  implicit val userFormat: Format[User] = (
      (__ \ "id").formatNullable[Long] ~
      (__ \ "email").format[String] ~
      (__ \ "firstName").format[String] ~
      (__ \ "lastName").format[String] ~
      (__ \ "lastLogin").formatNullable[Date] ~
      (__ \ "createdAt").formatNullable[Date] ~
      (__ \ "modifiedAt").formatNullable[Date])(User.apply, unlift(User.unapply))


  implicit object JobStatusFormat extends Format[JobStatus] {
    def reads(json: JsValue) = JsSuccess(json match {
      case JsString(s) => JobStatus.unapply(s).getOrElse(sys.error(s"Unknown JobStatus: $s"))
      case j           => sys.error(s"JobStatus should be a JsString. Found ${j.getClass}")
    })
    def writes(status: JobStatus) = JsString(status.name)
  }


  implicit val jobErrorFormat: Format[JobError] = (
      (__ \ "id").format[Long] ~
      (__ \ "jobId").format[Long] ~
      (__ \ "date").format[Date] ~
      (__ \ "status").format[JobStatus] ~
      (__ \ "message").formatNullable[String] ~
      (__ \ "endDate").formatNullable[Date])(JobError.apply, unlift(JobError.unapply))


  implicit val dataPointFormat: Format[DataPoint] = (
      (__ \ "metric").format[String] ~
      (__ \ "timestamp").format[Long] ~
      (__ \ "value").formatNullable[Double])(DataPoint.apply, unlift(DataPoint.unapply))


  def optJsArrayString(field: String): OFormat[Option[List[String]]] = new OFormat[Option[List[String]]] {
    val origFormat = (__ \ field).formatNullable[List[String]]

    def reads(json: JsValue) =
      origFormat.reads(json) map { opt =>
        opt map { s =>
          s.map(_.trim).filterNot(_.isEmpty)
        } collect {
          case a if(!a.isEmpty) => a
        }
      }

    def writes(o: Option[List[String]]) =
      origFormat.writes(o)
  }

  def jsArrayString(field: String): OFormat[List[String]] = new OFormat[List[String]] {
    val origFormat = (__ \ field).format[List[String]]

    def reads(json: JsValue) =
      origFormat.reads(json) map { ls =>
        ls.filterNot(_.trim.isEmpty)
      }

    def writes(o: List[String]) =
      origFormat.writes(o)
  }

  implicit object AlertKeyFormat extends Format[AlertKey] {
    val TYPE  = "type"
    val LABEL = "label"
    val VALUE = "value"

    def reads(json: JsValue) = {
      json \ TYPE match {
        case JsString("email")     => JsSuccess(EmailAlertKey((json \ LABEL).as[String], (json \ VALUE).as[String]))
        case JsString("pagerduty") => JsSuccess(PagerDutyAlertKey((json \ LABEL).as[String], (json \ VALUE).as[String]))
        case JsString("victorops") => JsSuccess(VictorOpsAlertKey((json \ LABEL).as[String], (json \ VALUE).as[String]))
        case _                     => sys.error(s"Unknown alert key type")
      }
    }

    def writes(k: AlertKey) = k match {
      case EmailAlertKey(label, value)     => Json.obj(TYPE -> "email", LABEL -> label, VALUE -> value)
      case PagerDutyAlertKey(label, value) => Json.obj(TYPE -> "pagerduty", LABEL -> label, VALUE -> value)
      case VictorOpsAlertKey(label, value) => Json.obj(TYPE -> "victorops", LABEL -> label, VALUE -> value)
    }
  }


  implicit val jobFormat: Format[Job] = (
    (__ \ "id").formatNullable[Long] ~
      (__ \ "userId").format[Long] ~
      (__ \ "appId").format[Long] ~
      (__ \ "name").format[String] ~
      (__ \ "cronExpr").format[String] ~
      jsArrayString("metrics") ~
      (__ \ "monitorExpr").formatNullable[String] ~
      (__ \ "minutes").formatNullable[Int] ~
      (__ \ "toDate").formatNullable[String] ~
      (__ \ "description").formatNullable[String] ~
      (__ \ "active").format[Boolean] ~
      (__ \ "status").formatNullable[JobStatus] ~
      (__ \ "lastRun").formatNullable[Date] ~
      (__ \ "nextRun").formatNullable[Date] ~
      (__ \ "alertKeys").formatNullable[List[AlertKey]] ~
      (__ \ "errorTimeout").format[Int] ~
      (__ \ "createdAt").formatNullable[Date] ~
      (__ \ "modifiedAt").formatNullable[Date] ~
      (__ \ "deletedAt").formatNullable[Date])(Job.apply, unlift(Job.unapply))


   implicit val monitorOutputWrites = (
      (__ \ "status").write[JobStatus] ~
      (__ \ "output").write[String] ~
      (__ \ "graph_data").write[JsValue])(unlift(MonitorOutput.unapply))


  implicit object TimeSeriesFormat extends Format[TimeSeries] {
    def reads(json: JsValue) = json match {
      case JsArray(s) => JsSuccess(s.map {
          case JsArray(d) => d.map(dataPointFormat.reads(_).get)
          case _          => sys.error("Expected JsArray")
        })
      case _          => JsError()
    }
    def writes(ts: TimeSeries) = JsArray(ts.map(outer => JsArray(outer.map(dataPointFormat.writes(_)))))
  }

  implicit def jobToNamespace(job: Job): JsObject = {
    JsObject(Seq(
        "jobId"   -> JsNumber(job.id.getOrElse(-1L).toLong),
        "name"    -> JsString(job.name),
        "minutes" -> JsNumber(job.minutes.getOrElse(Monitor.minutes).toInt)))
  }

  implicit def timeSeriesToJson(series: TimeSeries): JsValue = TimeSeriesFormat.writes(series)
}

object Constants {
  val ERROR_TIMEOUT = 60
}
