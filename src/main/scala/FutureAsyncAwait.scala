import scala.concurrent._
import scala.concurrent.duration._
import ExecutionContext.Implicits.global
import scala.async.Async.{async, await}
import scala.util._

class FutureAsyncAwait {

    def calcV1(variables: Map[String, Promise[Double]]): Unit = {
        async { 
            await { variables("V2").future } + await { variables("V3").future }
        }.onComplete { v => variables("V1").tryComplete(v) }
    }

    def calcV2(variables: Map[String, Promise[Double]]): Unit = variables("V2").success(1.0)

    def calcV3(variables: Map[String, Promise[Double]]): Unit = variables("V3").success(2.0)

    def calcV4(variables: Map[String, Promise[Double]]): Unit = {
        async { 
            await { variables("V5").future } + 1.0
        }.onComplete { v => variables("V4").tryComplete(v) }
    }

    def calcV5(variables: Map[String, Promise[Double]]): Unit = variables("V5").failure(new Exception("V5 failed"))

    val calculators = Map[String, (Map[String, Promise[Double]] => Unit)](
        "V1" -> calcV1,
        "V2" -> calcV2,
        "V3" -> calcV3,
        "V4" -> calcV4,
        "V5" -> calcV5
    )

    def test(): Unit = {
        val variables: Map[String, Promise[Double]] = calculators.keys.map { v => v -> Promise[Double]() }.toMap
        variables.foreach { case (name, promise) =>
            promise.future.onComplete { value => println(name + " result: " + value) }
        }
        calculators.foreach { case (name, calculator) => 
            calculator(variables)
        }
        println(Await.result(variables("V1").future, 1 second))
    }

}
