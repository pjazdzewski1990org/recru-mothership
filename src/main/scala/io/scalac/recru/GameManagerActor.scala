package io.scalac.recru

import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.pattern.ask
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.util.Timeout
import io.scalac.recru.GameActor.JoinResult
import io.scalac.recru.Model._

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

object GameManagerActor {
  sealed trait GameManagerCommand
  case class FindGameForPlayer(player: Player) extends GameManagerCommand
  case class MakeAMove(game: GameId, player: Player, whichColorToMove: Color, move: Move) extends GameManagerCommand

  case class GameStarted(players: Set[Player]) extends GameManagerCommand

  sealed trait FindGameResult
  case class GameFound(game: GameId, listenOn: String, colorAssigned: Color) extends FindGameResult

  sealed trait MakeAMoveResult
  case object NotYourTurn extends MakeAMoveResult
  case object Moved extends MakeAMoveResult

  def props(messages: Messages) =
    Props(classOf[GameManagerActor], messages, 30.seconds)
}

object GameManagerInternals {
  case class CurrentlyWaitingGame(id: GameId, ref: ActorRef)
}

class GameManagerActor(messages: Messages, playersWaitTimeout: FiniteDuration) extends Actor with ActorLogging {
  import GameManagerActor._
  import GameManagerInternals._

  implicit val timeout = Timeout(3, TimeUnit.SECONDS)
  implicit val ec: ExecutionContext = context.dispatcher

  def handleCommands(gamesRunning: Map[GameId, ActorRef],
                     gameWaiting: Option[CurrentlyWaitingGame]): Receive = {
    case msg: FindGameForPlayer if gameWaiting.isDefined =>
      tryJoining(gameWaiting.get, msg)

    case msg: FindGameForPlayer if gameWaiting.isEmpty =>
      val gid = GameId(UUID.randomUUID().toString)
      val gameRef = context.actorOf(GameActor.props(gid, self, messages, playersWaitTimeout = playersWaitTimeout))
      val openGame = CurrentlyWaitingGame(gid, gameRef)
      tryJoining(openGame, msg)

      context.become(handleCommands(gamesRunning, Option(openGame)))

    case GameStarted(_) if gameWaiting.isDefined =>
      val gameThatStarted = gameWaiting.get
      context.become(handleCommands(gamesRunning + (gameThatStarted.id -> gameThatStarted.ref), None))

    case MakeAMove(gid, player, colorToMove, move) =>
      val replyTo = sender()
      gamesRunning.get(gid).map { ref =>
        log.info("Player {} is going to move in {}", player, gid)
        (ref ? GameActor.PlayerMoves(player, colorToMove, move)).mapTo[GameActor.MoveResult].map {
          case GameActor.Moved =>
            replyTo ! GameManagerActor.Moved
          case GameActor.NotYourTurn =>
            replyTo ! GameManagerActor.NotYourTurn
        }
      }.getOrElse {
        replyTo ! GameManagerActor.NotYourTurn //TODO: be more precise
      }
  }

  //TODO: this signature is not quite right
  private def tryJoining(gameWaiting: CurrentlyWaitingGame, request: FindGameForPlayer) = {
    val replyTo = sender()
    val retryWith = self

    (gameWaiting.ref ? GameActor.JoinGame(request.player)).mapTo[JoinResult].foreach {
      case GameActor.Joined(color) =>
        log.info("Player {} joined {}", request.player, gameWaiting.id)
        replyTo ! GameFound(gameWaiting.id, messages.listenLocation, color)
      case _ =>
        // we will try again in a moment, but we need to overwrite the sender so reply will arrive correctly
        retryWith.tell(request, replyTo)
    }
  }

  override def receive: Receive = handleCommands(Map.empty, None)
}
