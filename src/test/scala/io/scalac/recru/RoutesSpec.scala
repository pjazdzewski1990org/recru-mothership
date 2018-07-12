package io.scalac.recru

import akka.http.scaladsl.model.StatusCodes
import io.scalac.recru.Model.{Color, GameId, Move, Red}
import org.scalatest.{FlatSpecLike, MustMatchers}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import io.scalac.recru.Protocol._
import io.scalac.recru.Protocol.IncomingPlayer
import io.scalac.recru.Signals.SignalListenLocation
import spray.json.{JsObject, JsString}

import scala.concurrent.Future

class RoutesSpec extends FlatSpecLike with MustMatchers with ScalatestRouteTest {

  val fakeGame = new GameService {
    override def searchForAGame(p: Model.Player): Future[GameService.GameJoined] = Future.successful(
      GameService.GameJoined(GameId("game"), SignalListenLocation("kafka-topic"), Red)
    )
    override def move(game: Model.GameId, p: Model.Player, color: Color, move: Move): Future[GameService.MoveResult] = Future.successful(
      GameService.Moved
    )
  }

  "Routes" should "allow creating games" in {
    val r = new Routes(fakeGame).router
    Post("/game", IncomingPlayer("player1")) ~> r ~> check{
      status mustBe StatusCodes.OK
      responseAs[JsObject].fields("gameId") mustBe JsString("game")
    }
  }

  it should "allow making moves" in {
    val r = new Routes(fakeGame).router
    Post("/game/e3-42bb-46a6-a286-8c779fa1f7b9", IncomingMove("player1", "red", 2)) ~> r ~> check{
      status mustBe StatusCodes.OK
    }
  }

  it should "reject invalid moves" in {
    val rejectingGame = new GameService {
      override def searchForAGame(p: Model.Player): Future[GameService.GameJoined] = ???
      override def move(game: Model.GameId, p: Model.Player, color: Color, move: Move): Future[GameService.MoveResult] = Future.successful(
        GameService.InvalidMove
      )
    }
    val r = new Routes(rejectingGame).router
    Post("/game/e3-42bb-46a6-a286-8c779fa1f7b9", IncomingMove("player1", "red", 9999)) ~> r ~> check{
      status mustBe StatusCodes.BadRequest
      responseAs[JsObject].fields("errorCode") mustBe JsString("MOVE")
    }
  }

  it should "reject moves out of order" in {
    val rejectingGame = new GameService {
      override def searchForAGame(p: Model.Player): Future[GameService.GameJoined] = ???
      override def move(game: Model.GameId, p: Model.Player, color: Color, move: Move): Future[GameService.MoveResult] = Future.successful(
        GameService.WrongTurn
      )
    }
    val r = new Routes(rejectingGame).router
    Post("/game/e3-42bb-46a6-a286-8c779fa1f7b9", IncomingMove("player1", "red", 2)) ~> r ~> check{
      status mustBe StatusCodes.BadRequest
      responseAs[JsObject].fields("errorCode") mustBe JsString("TURN")
    }
  }
}
