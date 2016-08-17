package hmda.api.http

import java.io.File

import akka.event.{ LoggingAdapter, NoLogging }
import akka.http.scaladsl.model._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.util.Timeout
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import com.typesafe.config.ConfigFactory
import hmda.api.RequestHeaderUtils
import hmda.api.model._
import hmda.model.fi._
import hmda.persistence.demo.DemoData
import org.scalatest.{ BeforeAndAfterAll, MustMatchers, WordSpec }
import hmda.persistence.institutions.InstitutionPersistence._
import org.iq80.leveldb.util.FileUtils

import scala.concurrent.duration._

class InstitutionsHttpApiSpec extends WordSpec with MustMatchers with ScalatestRouteTest
    with InstitutionsHttpApi with BeforeAndAfterAll with RequestHeaderUtils {

  override val log: LoggingAdapter = NoLogging
  override implicit val timeout: Timeout = Timeout(5.seconds)

  val ec = system.dispatcher

  override def beforeAll(): Unit = {
    createInstitutions(system)
    DemoData.loadData(system)
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    system.terminate()
    val config = ConfigFactory.load()
    val snapshotStore = new File(config.getString("akka.persistence.snapshot-store.local.dir"))
    FileUtils.deleteRecursively(snapshotStore)
  }

  "Institutions HTTP API" must {
    "return a list of existing institutions" in {
      getWithCfpbHeaders("/institutions") ~> institutionsRoutes ~> check {
        status mustBe StatusCodes.OK
        val institutionsWrapped = DemoData.institutions.map(i => InstitutionWrapper(i.id, i.name, i.status))
        responseAs[Institutions] mustBe Institutions(institutionsWrapped)
      }
    }

    "return an institution by id" in {
      getWithCfpbHeaders("/institutions/12345") ~> institutionsRoutes ~> check {
        status mustBe StatusCodes.OK
        val institution = DemoData.institutions.head
        val institutionWrapped = InstitutionWrapper(institution.id, institution.name, institution.status)
        val filings = DemoData.filings.filter(f => f.institutionId == institution.id.toString)
        responseAs[InstitutionDetail] mustBe InstitutionDetail(institutionWrapped, filings.reverse)
      }
      getWithCfpbHeaders("/institutions/xxxx") ~> institutionsRoutes ~> check {
        status mustBe StatusCodes.NotFound
        responseAs[ErrorResponse] mustBe ErrorResponse(404, "Institution xxxx not found", "institutions/xxxx")
      }
    }

    "return an institution's summary" in {
      getWithCfpbHeaders("/institutions/12345/summary") ~> institutionsRoutes ~> check {
        status mustBe StatusCodes.OK
        val summary = DemoData.institutionSummary
        val institutionSummary = InstitutionSummary(summary._1.toString, summary._2, summary._3)
        responseAs[InstitutionSummary] mustBe institutionSummary
      }
    }

    "return a list of submissions for a financial institution" in {
      getWithCfpbHeaders("/institutions/12345/filings/2017") ~> institutionsRoutes ~> check {
        status mustBe StatusCodes.OK
        val filing = Filing("2017", "12345", NotStarted)
        responseAs[FilingDetail] mustBe FilingDetail(filing, DemoData.newSubmissions.reverse)
      }
      getWithCfpbHeaders("/institutions/12345/filings/xxxx") ~> institutionsRoutes ~> check {
        status mustBe StatusCodes.NotFound
        responseAs[ErrorResponse] mustBe ErrorResponse(404, "xxxx filing not found for institution 12345", "institutions/12345/filings/xxxx")
      }
      getWithCfpbHeaders("/institutions/xxxxx/filings/2017") ~> institutionsRoutes ~> check {
        status mustBe StatusCodes.NotFound
        responseAs[ErrorResponse] mustBe ErrorResponse(404, "2017 filing not found for institution xxxxx", "institutions/xxxxx/filings/2017")
      }
    }

    "create a new submission" in {
      postWithCfpbHeaders("/institutions/12345/filings/2017/submissions") ~> institutionsRoutes ~> check {
        status mustBe StatusCodes.Created
        responseAs[Submission] mustBe Submission(DemoData.newSubmissions.size + 1, Created)
      }
    }

    "fail creating a new submission for a non existent institution" in {
      postWithCfpbHeaders("/institutions/xxxxx/filings/2017/submissions") ~> institutionsRoutes ~> check {
        status mustBe StatusCodes.NotFound
        responseAs[ErrorResponse] mustBe ErrorResponse(404, "2017 filing not found for institution xxxxx", "institutions/xxxxx/filings/2017/submissions")
      }
    }

    "fail creating a new submission for a non existent filing period" in {
      postWithCfpbHeaders("/institutions/12345/filings/2001/submissions") ~> institutionsRoutes ~> check {
        status mustBe StatusCodes.NotFound
        responseAs[ErrorResponse] mustBe ErrorResponse(404, "2001 filing not found for institution 12345", "institutions/12345/filings/2001/submissions")
      }
    }

    "return proper response when uploading a HMDA file" in {
      val csv = "1|0123456789|9|201301171330|2013|99-9999999|900|MIKES SMALL BANK   XXXXXXXXXXX|1234 Main St       XXXXXXXXXXXXXXXXXXXXX|Sacramento         XXXXXX|CA|99999-9999|MIKES SMALL INC    XXXXXXXXXXX|1234 Kearney St    XXXXXXXXXXXXXXXXXXXXX|San Francisco      XXXXXX|CA|99999-1234|Mrs. Krabappel     XXXXXXXXXXX|916-999-9999|999-753-9999|krabappel@gmail.comXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX\n" +
        "2|0123456789|9|ABCDEFGHIJKLMNOPQRSTUVWXY|20130117|4|3|2|1|10000|1|5|20130119|06920|06|034|0100.01|4|5|7|4|3|2|1|8|7|6|5|4|1|2|9000|0|9|8|7|01.05|2|4\n" +
        "2|0123456789|9|ABCDEFGHIJKLMNOPQRSTUVWXY|20130117|4|3|2|1|10000|1|5|20130119|06920|06|034|0100.01|4|5|7|4|3|2|1|8|7|6|5|4|1|2|9000|0|9|8|7|01.05|2|4\n" +
        "2|0123456789|9|ABCDEFGHIJKLMNOPQRSTUVWXY|20130117|4|3|2|1|10000|1|5|20130119|06920|06|034|0100.01|4|5|7|4|3|2|1|8|7|6|5|4|1|2|9000|0|9|8|7|01.05|2|4"

      val file = multiPartFile(csv, "sample.txt")

      postWithCfpbHeaders("/institutions/12345/filings/2017/submissions/1", file) ~> institutionsRoutes ~> check {
        status mustBe StatusCodes.OK
        responseAs[String] mustBe "uploaded"
      }
    }

    "return 400 when trying to upload the wrong file" in {
      val badContent = "qdemd"
      val file = multiPartFile(badContent, "sample.dat")
      postWithCfpbHeaders("/institutions/12345/filings/2017/submissions/1", file) ~> institutionsRoutes ~> check {
        status mustBe StatusCodes.BadRequest
        responseAs[ErrorResponse] mustBe ErrorResponse(400, "Invalid File Format", "institutions/12345/filings/2017/submissions/1")
      }
    }

  }

  /*
  "Institutions API Authorization" must {

    // 'CFPB-HMDA-Username' header
    "reject requests to /institutions without 'CFPB-HMDA-Username' header" in {
      // Request the endpoint without username header (but with other headers)
      Get("/institutions").addHeader(institutionsHeader) ~> institutionsRoutes ~> check {
        status mustBe StatusCodes.Forbidden
        responseAs[ErrorResponse] mustBe ErrorResponse(403, "Unauthorized Access", "")
      }
    }
    "reject requests to /inst/id without 'CFPB-HMDA-Username' header" in {
      // Request the endpoint without username header (but with other headers)
      Get("/institutions/12345").addHeader(institutionsHeader) ~> institutionsRoutes ~> check {
        status mustBe StatusCodes.Forbidden
        responseAs[ErrorResponse] mustBe ErrorResponse(403, "Unauthorized Access", "")
      }
    }
    "reject requests to /inst/id/filings/p without 'CFPB-HMDA-Username' header" in {
      // Request the endpoint without username header (but with other headers)
      Get("/institutions/12345/filings/2017").addHeader(institutionsHeader) ~> institutionsRoutes ~> check {
        status mustBe StatusCodes.Forbidden
        responseAs[ErrorResponse] mustBe ErrorResponse(403, "Unauthorized Access", "")
      }
    }

    // 'CFPB-HMDA-Institutions' header
    "reject requests to /inst without 'CFPB-HMDA-Institutions' header" in {
      // Request the endpoint without institutions header (but with other headers)
      Get("/institutions").addHeader(usernameHeader) ~> institutionsRoutes ~> check {
        status mustBe StatusCodes.Forbidden
        responseAs[ErrorResponse] mustBe ErrorResponse(403, "Unauthorized Access", "")
      }
    }
    "reject requests to submission creation without 'CFPB-HMDA-Institutions' header" in {
      // Request the endpoint without institutions header (but with other headers)
      Post("/institutions/12345/filings/2017/submissions").addHeader(usernameHeader) ~> institutionsRoutes ~> check {
        status mustBe StatusCodes.Forbidden
        responseAs[ErrorResponse] mustBe ErrorResponse(403, "Unauthorized Access", "")
      }
    }
    "reject requests to submission summary without 'CFPB-HMDA-Institutions' header" in {
      Get("/institutions/12345/filings/2017").addHeader(usernameHeader) ~> institutionsRoutes ~> check {
        status mustBe StatusCodes.Forbidden
        responseAs[ErrorResponse] mustBe ErrorResponse(403, "Unauthorized Access", "")
      }
    }

  }
  */

  private def multiPartFile(contents: String, fileName: String) = {
    Multipart.FormData(Multipart.FormData.BodyPart.Strict(
      "file",
      HttpEntity(ContentTypes.`text/plain(UTF-8)`, contents),
      Map("filename" -> fileName)
    ))
  }

}
