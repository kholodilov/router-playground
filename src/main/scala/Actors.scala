import akka.actor._
import akka.event.LoggingReceive

import scala.concurrent._
import scala.concurrent.duration._
import scala.util.Try

class Actors {

    def test(): Unit = {

        val system = ActorSystem("RouterActorSystem")
        import system.dispatcher
        val requestHandler = system.actorOf(Props(new RequestHandler(List("V1", "V4", "V6"))), "request-handler")

        requestHandler ! "test message"

        Future(system.awaitTermination(5 seconds)).onFailure { case _ => system.shutdown() }
    }
}

class RequestHandler(variables: Seq[String]) extends Actor with ActorLogging {

    log.info(s"variables: $variables")

    def receive = LoggingReceive {
        case request =>
            log.info(s"$request received")
            context.system.shutdown()
    }
}

class VariableCalcualtor extends Actor with ActorLogging {

    def receive = LoggingReceive {
        case request =>
            log.info(s"$request received")
    }
}