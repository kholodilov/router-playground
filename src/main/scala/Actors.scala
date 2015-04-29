import akka.actor._
import akka.event.LoggingReceive
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import akka.io.IO

import spray.can.Http
import spray.routing.{HttpService, RequestContext}
import spray.httpx.SprayJsonSupport._
import spray.httpx.marshalling._
import spray.json.DefaultJsonProtocol._

import scala.concurrent._
import scala.concurrent.duration._
import scala.async.Async.{async, await}
import scala.util.Try
import scala.collection.mutable
import scala.util.Random

import Common._


class Actors {

    implicit val system = ActorSystem("RouterActorSystem")
    val service = system.actorOf(Props[RouterServiceActor], "router-service")

    def test(): Unit = {

        IO(Http) ! Http.Bind(service, "localhost", port = 8080)

    }

    def shutdown(): Unit = system.shutdown()

}

class RouterServiceActor extends Actor with ActorLogging with HttpService {

    import context.dispatcher
    def actorRefFactory = context

    def receive = runRoute {
        get {
            path("") { ctx =>
                val completeCallback = (result: Map[String, Double]) => ctx.complete(marshal(result))
                val id = Random.nextInt(Int.MaxValue)
                val requestHandler = context.actorOf(Props(new RequestHandler(strategies)), "request-handler-" + id)
                val resultsCollector = context.actorOf(Props(new ResultsCollector(List("V1", "V2", "V4", "V6"), requestHandler, completeCallback)), "results-collector-" + id)
            }
        }
    }

    val strategies = Map[String, VariableStrategy](
        "V1" -> new V1Strategy(),
        "V2" -> new StaticVariableStrategy("V2"),
        "V3" -> new StaticVariableStrategy("V3"),
        "V4" -> new V4Strategy(),
        "V5" -> new StaticVariableStrategy("V5"),
        "V6" -> new StaticVariableStrategy("V6")
    )

}

case object Finished

class ResultsCollector(variables: List[String], requestHandler: ActorRef, completeCallback: (Map[String, Double]) => Unit) extends Actor with ActorLogging {

    val results = mutable.HashMap.empty[String, Double]

    requestHandler ! VariablesRequest(variables)

    import context.dispatcher
    context.system.scheduler.scheduleOnce(3 seconds, self, Finished)

    def receive = LoggingReceive {
        case VariableResult(name, value) => {
            results += (name -> value)
            if (results.size == variables.size) {
                self ! Finished
            }
        }
        case Finished =>
            completeCallback(results.toMap)
            context.stop(self)
    }

    override def postStop() = println("STOPPED")

}

class RequestHandler(strategies: Map[String, VariableStrategy]) extends Actor with ActorLogging {

    val results = mutable.HashMap.empty[String, Promise[Double]]

    import context.dispatcher

    def receive = LoggingReceive {
        case VariablesRequest(variables) =>
            val newResults = mutable.HashMap.empty[String, Promise[Double]]
            variables.foreach { v =>
                if (results.contains(v)) {
                    newResults += (v -> results(v))
                }
                else {
                    val r = Promise[Double]()
                    results += (v -> r)
                    newResults += (v -> r)
                    context.actorOf(Props(new VariableCalculator(v, strategies(v))), v)
                }
            }
            newResults.foreach { case (v, r) => r.future.map(VariableResult(v, _)) pipeTo sender }
        case VariableResult(name, value) =>
            results(name).success(value)
    }
}

case class VariablesRequest(variables: List[String])
case class VariableResult(name: String, value: Double)
case class VariableFailure(name: String)

class VariableCalculator(name: String, strategy: VariableStrategy) extends Actor with ActorLogging {

    val dependencies: Map[String, Promise[Double]] = 
        strategy.dependencies().map { v => v -> Promise[Double]() }.toMap

    import context.dispatcher

    override def preStart() = {
        if (strategy.dependencies().nonEmpty) {
            context.parent ! VariablesRequest(strategy.dependencies())
        }
        strategy.calculate(dependencies)
                .map(VariableResult(name, _))
                .fallbackTo(Future.successful(VariableFailure(name)))
                .pipeTo(context.parent)
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
