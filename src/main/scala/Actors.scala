import akka.actor._
import akka.event.LoggingReceive
import akka.pattern.{ask, pipe}
import akka.util.Timeout

import scala.concurrent._
import scala.concurrent.duration._
import scala.async.Async.{async, await}
import scala.util.Try
import scala.collection.mutable

import Common._


class Actors {

    def test(): Unit = {

        val system = ActorSystem("RouterActorSystem")

        import system.dispatcher

        val strategies = Map[String, VariableStrategy](
            "V1" -> new V1Strategy(),
            "V2" -> new StaticVariableStrategy("V2"),
            "V3" -> new StaticVariableStrategy("V3"),
            "V4" -> new V4Strategy(),
            "V5" -> new StaticVariableStrategy("V5"),
            "V6" -> new StaticVariableStrategy("V6")
        )

        val requestHandler = system.actorOf(Props(new RequestHandler(strategies)), "request-handler")
        implicit val timeout = Timeout(5 seconds)
        val result = Try(Await.result(requestHandler ? VariablesRequest(List("V1", "V2", "V4", "V6")), timeout.duration))
        println("Result: " + result)

        system.shutdown()
    }
}

class RequestHandler(strategies: Map[String, VariableStrategy]) extends Actor with ActorLogging {

    val results = mutable.HashMap.empty[String, Promise[Double]]

    import context.dispatcher

    def receive = receiveInitialRequest

    def receiveInitialRequest = LoggingReceive {
        case request: VariablesRequest =>
            val initialResults = request.variables.map { v => v -> Promise[Double]() }.toMap
            initialResults.foreach { case (v, r) =>
                results += (v -> r)
                context.actorOf(Props(new VariableCalculator(v, strategies(v))), v)
            }
            allPromises(initialResults) pipeTo context.parent
            context.become(collectResults)
    }

    def collectResults = LoggingReceive {
        case VariableResult(name, value) =>
    }
}

case class VariablesRequest(variables: List[String])
case class VariableResult(name: String, value: Double)

class VariableCalculator(name: String, strategy: VariableStrategy) extends Actor with ActorLogging {

    val dependencies: Map[String, Promise[Double]] = 
        strategy.dependencies().map { v => v -> Promise[Double]() }.toMap

    import context.dispatcher

    override def preStart() = {
        if (strategy.dependencies().nonEmpty) {
            context.parent ! VariablesRequest(strategy.dependencies())
        }
        strategy.calculate(dependencies).map(VariableResult(name, _)).pipeTo(context.parent)
    }

    def receive = LoggingReceive {
        case VariableResult(name, value) =>
            dependencies(name).success(value)
    }
}

trait VariableStrategy {
    def calculate(dependencies: Map[String, Promise[Double]]): Future[Double]
    def dependencies(): List[String]
}

class StaticVariableStrategy(name: String)(implicit ec: ExecutionContext) extends VariableStrategy {

    def calculate(dependencies: Map[String, Promise[Double]]): Future[Double] = {
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

    def calculate(dependencies: Map[String, Promise[Double]]): Future[Double] = async { 
        await { dependencies("V2").future } + await { dependencies("V3").future }
    }

    def dependencies(): List[String] = List("V2", "V3")

}

class V4Strategy(implicit ec: ExecutionContext) extends VariableStrategy {
    
    def calculate(dependencies: Map[String, Promise[Double]]): Future[Double] = async { 
        await { dependencies("V5").future } + await { dependencies("V6").future }
    }

    def dependencies(): List[String] = List("V5", "V6")

}
