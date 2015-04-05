import akka.actor._
import akka.event.LoggingReceive
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent._
import scala.concurrent.duration._
import scala.async.Async.{async, await}
import scala.util.Try


class Actors {

    def test(): Unit = {

        val system = ActorSystem("RouterActorSystem")

        import system.dispatcher

        val strategies = Map[String, VariableStrategy](
            "V1" -> new V1Strategy(),
            "V2" -> new StaticVariableStrategy("V1"),
            "V3" -> new StaticVariableStrategy("V3"),
            "V4" -> new V4Strategy(),
            "V5" -> new StaticVariableStrategy("V5"),
            "V6" -> new StaticVariableStrategy("V6")
        )

        val requestHandler = system.actorOf(Props(new RequestHandler(strategies)), "request-handler")
        implicit val timeout = Timeout(5 seconds)
        val result = Await.result(requestHandler ? List("V1", "V2", "V4", "V6"), timeout.duration)
        println("Result: " + result)

        system.shutdown()
    }
}

class RequestHandler(strategies: Map[String, VariableStrategy]) extends Actor with ActorLogging {

    def receive = LoggingReceive {
        case request: List[String] =>
            sender ! "some result"
    }
}

class VariableCalculator extends Actor with ActorLogging {

    def receive = LoggingReceive {
        case request =>
            log.info(s"$request received")
    }
}

trait VariableStrategy {
    def calculate(dependencies: Map[String, Future[Double]]): Future[Double]
    def dependencies(): List[String]
}

class StaticVariableStrategy(name: String)(implicit ec: ExecutionContext) extends VariableStrategy {

    def calculate(dependencies: Map[String, Future[Double]]): Future[Double] = {
        name match {
            case "V2" => Future.successful(1.0)
            case "V3" => Future.successful(2.0)
            case "V5" => Future.failed(new Exception("V5 failed"))
            case "V6" => Future {
                blocking { Thread.sleep(5000) }
                println("V6: I'm alive")
                666.0
            }
        }

    }

    def dependencies(): List[String] = List()
}

class V1Strategy(implicit ec: ExecutionContext) extends VariableStrategy {

    def calculate(dependencies: Map[String, Future[Double]]): Future[Double] = async { 
        await { dependencies("V2") } + await { dependencies("V3") }
    }

    def dependencies(): List[String] = List("V2", "V3")

}

class V4Strategy(implicit ec: ExecutionContext) extends VariableStrategy {
    
    def calculate(dependencies: Map[String, Future[Double]]): Future[Double] = async { 
        await { dependencies("V5") } + await { dependencies("V6") }
    }

    def dependencies(): List[String] = List("V5", "V6")

}
