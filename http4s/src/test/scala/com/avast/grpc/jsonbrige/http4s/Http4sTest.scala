package com.avast.grpc.jsonbrige.http4s

import java.util.concurrent.{ExecutorService, Executors}

import cats.data.NonEmptyList
import cats.effect.IO
import com.avast.grpc.jsonbridge._
import com.avast.grpc.jsonbridge.test.TestApi
import com.avast.grpc.jsonbridge.test.TestApi.{GetRequest, GetResponse}
import com.avast.grpc.jsonbridge.test.TestApiServiceGrpc.{TestApiServiceFutureStub, TestApiServiceImplBase}
import io.grpc.stub.StreamObserver
import io.grpc.{Status, StatusException}
import org.http4s.headers.{`Content-Length`, `Content-Type`}
import org.http4s.{Headers, MediaType, Method, Request, Uri}
import org.scalatest.FunSuite
import org.scalatest.concurrent.ScalaFutures

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class Http4sTest extends FunSuite with ScalaFutures {
  implicit val executor: ExecutorService = Executors.newCachedThreadPool()

  case class MyRequest(names: Seq[String])

  case class MyResponse(results: Map[String, Int])

  trait MyApi {
    def get(request: MyRequest): Future[Either[Status, MyResponse]]

    def get2(request: MyRequest): Future[Either[Status, MyResponse]]
  }

  test("basic") {
    val bridge = new TestApiServiceImplBase {
      override def get(request: GetRequest, responseObserver: StreamObserver[TestApi.GetResponse]): Unit = {
        assertResult(Seq("abc", "def"))(request.getNamesList.asScala)
        responseObserver.onNext(GetResponse.newBuilder().putResults("name", 42).build())
        responseObserver.onCompleted()
      }
    }.createGrpcJsonBridge[TestApiServiceFutureStub]()

    val service = Http4s(Configuration.Default)(bridge)

    val Some(response) = service
      .apply(
        Request[IO](method = Method.POST,
                    uri = Uri.fromString(s"${classOf[TestApiServiceImplBase].getName.replace("$", ".")}/Get").getOrElse(fail()))
          .withBody(""" { "names": ["abc","def"] } """)
          .unsafeRunSync()
      )
      .value
      .unsafeRunSync()

    assertResult(org.http4s.Status.Ok)(response.status)

    assertResult("""{
                   |  "results": {
                   |    "name": 42
                   |  }
                   |}""".stripMargin)(response.as[String].unsafeRunSync())

    assertResult(
      Headers(
        `Content-Type`(MediaType.`application/json`),
        `Content-Length`.fromLong(37).getOrElse(fail())
      ))(response.headers)

  }

  test("path prefix") {
    val bridge = new TestApiServiceImplBase {
      override def get(request: GetRequest, responseObserver: StreamObserver[TestApi.GetResponse]): Unit = {
        assertResult(Seq("abc", "def"))(request.getNamesList.asScala)
        responseObserver.onNext(GetResponse.newBuilder().putResults("name", 42).build())
        responseObserver.onCompleted()
      }
    }.createGrpcJsonBridge[TestApiServiceFutureStub]()

    val configuration = Configuration.Default.copy(pathPrefix = Some(NonEmptyList.of("abc", "def")))

    val service = Http4s(configuration)(bridge)

    val Some(response) = service
      .apply(
        Request[IO](method = Method.POST,
                    uri = Uri.fromString(s"/abc/def/${classOf[TestApiServiceImplBase].getName.replace("$", ".")}/Get").getOrElse(fail()))
          .withBody(""" { "names": ["abc","def"] } """)
          .unsafeRunSync()
      )
      .value
      .unsafeRunSync()

    assertResult(org.http4s.Status.Ok)(response.status)

    assertResult("""{
                   |  "results": {
                   |    "name": 42
                   |  }
                   |}""".stripMargin)(response.as[String].unsafeRunSync())

    assertResult(
      Headers(
        `Content-Type`(MediaType.`application/json`),
        `Content-Length`.fromLong(37).getOrElse(fail())
      ))(response.headers)

  }

  test("bad request after wrong request") {
    val bridge = new TestApiServiceImplBase {
      override def get(request: GetRequest, responseObserver: StreamObserver[TestApi.GetResponse]): Unit = {
        assertResult(Seq("abc", "def"))(request.getNamesList.asScala)
        responseObserver.onNext(GetResponse.newBuilder().putResults("name", 42).build())
        responseObserver.onCompleted()
      }
    }.createGrpcJsonBridge[TestApiServiceFutureStub]()

    val service = Http4s(Configuration.Default)(bridge)

    val Some(response) = service
      .apply(
        Request[IO](method = Method.POST,
                    uri = Uri.fromString(s"${classOf[TestApiServiceImplBase].getName.replace("$", ".")}/Get").getOrElse(fail()))
          .withBody("")
          .unsafeRunSync()
      )
      .value
      .unsafeRunSync()

    assertResult(org.http4s.Status.BadRequest)(response.status)
  }

  test("propagate user-specified status") {
    val bridge = new TestApiServiceImplBase {
      override def get(request: GetRequest, responseObserver: StreamObserver[TestApi.GetResponse]): Unit = {
        responseObserver.onError(new StatusException(Status.PERMISSION_DENIED))
      }
    }.createGrpcJsonBridge[TestApiServiceFutureStub]()

    val service = Http4s(Configuration.Default)(bridge)

    val Some(response) = service
      .apply(
        Request[IO](method = Method.POST,
                    uri = Uri.fromString(s"${classOf[TestApiServiceImplBase].getName.replace("$", ".")}/Get").getOrElse(fail()))
          .withBody(""" { "names": ["abc","def"] } """)
          .unsafeRunSync()
      )
      .value
      .unsafeToFuture()
      .futureValue

    assertResult(org.http4s.Status.Forbidden)(response.status)
  }

  test("provide service info") {
    val bridge = new TestApiServiceImplBase {}.createGrpcJsonBridge[TestApiServiceFutureStub]()

    val service = Http4s(Configuration.Default)(bridge)

    val Some(response) = service
      .apply(
        Request[IO](method = Method.GET,
                    uri = Uri.fromString(s"${classOf[TestApiServiceImplBase].getName.replace("$", ".")}").getOrElse(fail()))
      )
      .value
      .unsafeRunSync()

    assertResult(org.http4s.Status.Ok)(response.status)

    assertResult("TestApiService/Get\nTestApiService/Get2")(response.as[String].unsafeRunSync())
  }
}