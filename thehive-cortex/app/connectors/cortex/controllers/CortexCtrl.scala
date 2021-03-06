package connectors.cortex.controllers

import javax.inject.{ Inject, Singleton }

import scala.concurrent.{ ExecutionContext, Future }

import play.api.Logger
import play.api.http.Status
import play.api.libs.json.{ JsObject, Json }
import play.api.mvc._
import play.api.routing.SimpleRouter
import play.api.routing.sird.{ DELETE, GET, PATCH, POST, UrlContext }

import akka.actor.ActorSystem

import org.elastic4play.{ BadRequestError, NotFoundError, Timed }
import org.elastic4play.controllers.{ Authenticated, Fields, FieldsBodyParser, Renderer }
import org.elastic4play.models.JsonFormat.baseModelEntityWrites
import org.elastic4play.services.{ Agg, AuxSrv, QueryDSL, QueryDef }
import org.elastic4play.services.JsonFormat.{ aggReads, queryReads }
import connectors.Connector
import connectors.cortex.models.JsonFormat.analyzerFormats
import connectors.cortex.services.{ CortexConfig, CortexSrv }
import models.HealthStatus.Type
import models.{ HealthStatus, Roles }

@Singleton
class CortexCtrl @Inject() (
    reportTemplateCtrl: ReportTemplateCtrl,
    cortexConfig: CortexConfig,
    cortexSrv: CortexSrv,
    auxSrv: AuxSrv,
    authenticated: Authenticated,
    fieldsBodyParser: FieldsBodyParser,
    renderer: Renderer,
    components: ControllerComponents,
    implicit val ec: ExecutionContext,
    implicit val system: ActorSystem) extends AbstractController(components) with Connector with Status {

  val name = "cortex"
  private[CortexCtrl] lazy val logger = Logger(getClass)

  override def status: Future[JsObject] =
    Future.traverse(cortexConfig.instances)(instance ⇒ instance.status())
      .map { statusDetails ⇒
        val distinctStatus = statusDetails.map(s ⇒ (s \ "status").as[String]).toSet
        val healthStatus = if (distinctStatus.contains("OK")) {
          if (distinctStatus.size > 1) "WARNING" else "OK"
        }
        else "ERROR"
        Json.obj(
          "enabled" → true,
          "servers" → statusDetails,
          "status" → healthStatus)
      }

  override def health: Future[Type] = {
    Future.traverse(cortexConfig.instances)(instance ⇒ instance.health())
      .map { healthStatus ⇒
        val distinctStatus = healthStatus.toSet
        if (distinctStatus.contains(HealthStatus.Ok)) {
          if (distinctStatus.size > 1) HealthStatus.Warning else HealthStatus.Ok
        }
        else if (distinctStatus.contains(HealthStatus.Error)) HealthStatus.Error
        else HealthStatus.Warning
      }
  }

  val router = SimpleRouter {
    case POST(p"/job") ⇒ createJob
    case GET(p"/job/$jobId<[^/]*>") ⇒ getJob(jobId)
    case POST(p"/job/_search") ⇒ findJob
    case POST(p"/job/_stats") ⇒ statsJob
    case GET(p"/analyzer/$analyzerId<[^/]*>") ⇒ getAnalyzer(analyzerId)
    case GET(p"/analyzer/type/$dataType<[^/]*>") ⇒ getAnalyzerFor(dataType)
    case GET(p"/analyzer") ⇒ listAnalyzer
    case POST(p"/report/template/_search") ⇒ reportTemplateCtrl.find()
    case POST(p"/report/template") ⇒ reportTemplateCtrl.create()
    case GET(p"/report/template/$caseTemplateId<[^/]*>") ⇒ reportTemplateCtrl.get(caseTemplateId)
    case PATCH(p"/report/template/$caseTemplateId<[^/]*>") ⇒ reportTemplateCtrl.update(caseTemplateId)
    case DELETE(p"/report/template/$caseTemplateId<[^/]*>") ⇒ reportTemplateCtrl.delete(caseTemplateId)
    case GET(p"/report/template/content/$analyzerId<[^/]*>/$reportType<[^/]*>") ⇒ reportTemplateCtrl.getContent(analyzerId, reportType)
    case POST(p"/report/template/_import") ⇒ reportTemplateCtrl.importTemplatePackage
    case r ⇒ throw NotFoundError(s"${r.uri} not found")
  }

  @Timed
  def createJob: Action[Fields] = authenticated(Roles.write).async(fieldsBodyParser) { implicit request ⇒
    val analyzerId = request.body.getString("analyzerId").getOrElse(throw BadRequestError(s"analyzerId is missing"))
    val artifactId = request.body.getString("artifactId").getOrElse(throw BadRequestError(s"artifactId is missing"))
    val cortexId = request.body.getString("cortexId")
    cortexSrv.submitJob(cortexId, analyzerId, artifactId).map { job ⇒
      renderer.toOutput(OK, job)
    }
  }

  @Timed
  def getJob(jobId: String): Action[AnyContent] = authenticated(Roles.read).async { implicit request ⇒
    cortexSrv.getJob(jobId).map { job ⇒
      renderer.toOutput(OK, job)
    }
  }

  @Timed
  def findJob: Action[Fields] = authenticated(Roles.read).async(fieldsBodyParser) { implicit request ⇒
    val query = request.body.getValue("query").fold[QueryDef](QueryDSL.any)(_.as[QueryDef])
    val range = request.body.getString("range")
    val sort = request.body.getStrings("sort").getOrElse(Nil)

    val (jobs, total) = cortexSrv.find(query, range, sort)
    val jobWithoutReport = auxSrv.apply(jobs, 0, withStats = false, removeUnaudited = true)
    renderer.toOutput(OK, jobWithoutReport, total)
  }

  @Timed
  def statsJob: Action[Fields] = authenticated(Roles.read).async(fieldsBodyParser) { implicit request ⇒
    val query = request.body.getValue("query")
      .fold[QueryDef](QueryDSL.any)(_.as[QueryDef])
    val aggs = request.body.getValue("stats")
      .getOrElse(throw BadRequestError("Parameter \"stats\" is missing")).as[Seq[Agg]]
    cortexSrv.stats(query, aggs).map(s ⇒ Ok(s))
  }

  @Timed
  def getAnalyzer(analyzerId: String): Action[AnyContent] = authenticated(Roles.read).async { implicit request ⇒
    cortexSrv.getAnalyzer(analyzerId).map { analyzer ⇒
      renderer.toOutput(OK, analyzer)
    }
  }

  @Timed
  def getAnalyzerFor(dataType: String): Action[AnyContent] = authenticated(Roles.read).async { implicit request ⇒
    cortexSrv.getAnalyzersFor(dataType).map { analyzers ⇒
      renderer.toOutput(OK, analyzers)
    }
  }

  @Timed
  def listAnalyzer: Action[AnyContent] = authenticated(Roles.read).async { implicit request ⇒
    cortexSrv.listAnalyzer.map { analyzers ⇒
      renderer.toOutput(OK, analyzers)
    }
  }
}
