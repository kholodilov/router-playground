import scala.concurrent._
import scala.concurrent.duration._
import ExecutionContext.Implicits.global
import scala.async.Async.{async, await}
import scala.util._
import scala.collection.mutable.ListBuffer

class FutureAsyncAwait {

    import Common._

    def calcV1(variables: Map[String, Promise[Double]]): Future[Double] = async { 
        await { variables("V2").future } + await { variables("V3").future }
    }

    def calcV2(variables: Map[String, Promise[Double]]): Future[Double] = Future { 1.0 }

    def calcV3(variables: Map[String, Promise[Double]]): Future[Double] = Future { 2.0 }

    def calcV4(variables: Map[String, Promise[Double]]): Future[Double] = async { 
        await { variables("V5").future } + 1.0
    }

    def calcV5(variables: Map[String, Promise[Double]]): Future[Double] = Future { throw new Exception("V5 failed") }

    def calcV6(variables: Map[String, Promise[Double]]): Future[Double] = Future { 
        blocking { Thread.sleep(5000) }
        println("calcV6: I'm alive")
        666.0
    }

    val calculators = Map[String, (Map[String, Promise[Double]] => Future[Double])](
        "V1" -> calcV1,
        "V2" -> calcV2,
        "V3" -> calcV3,
        "V4" -> calcV4,
        "V5" -> calcV5,
        "V6" -> calcV6
    )

    def timeout(t: Duration): Future[Unit] = Future {
      blocking { Thread.sleep(t.toMillis) }
      throw new TimeoutException()
    }

    def test(): Unit = {
        
        val variables: Map[String, Promise[Double]] = calculators.keys.map { v => v -> Promise[Double]() }.toMap

        // get each variable when it's ready
        variables.foreach { case (name, promise) =>
            promise.future.onComplete { value => println(name + " result: " + value) }
        }

        // get total result when all variables are ready
        allPromises(variables).onComplete { result =>
            println("All variables result: " + result)
        }

        // set timeout - if variable is not ready within it, its promise will be failed
        timeout(1 seconds).onComplete { _ =>
            variables.foreach { case (_, promise) =>
                promise.tryFailure(new TimeoutException())
            }
        }

        // start calculation
        calculators.foreach { case (name, calculate) => 
            variables(name).tryCompleteWith(calculate(variables))
        }

        println("bootstrap finished")
    }

}
