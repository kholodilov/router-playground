import scala.concurrent._
import scala.async.Async.{async, await}
import scala.util._
import scala.collection.mutable.ListBuffer

object Common {

    def tryPromise[T](p: Promise[T])(implicit ec: ExecutionContext): Promise[Try[T]] = {
        val tryP = Promise[Try[T]]()
        p.future.onComplete { value: Try[T] =>
            tryP.success(value)
        }
        tryP
    }

    def allPromises[K, V](mp: Map[K, Promise[V]])(implicit ec: ExecutionContext): Future[Map[K, Try[V]]] = async {
        var entries = mp.toList
        val result = new ListBuffer[(K, Try[V])]()
        while (entries != Nil) {
            val (key, promise) = entries.head
            result += new Tuple2(key, await { tryPromise(promise).future })
            entries = entries.tail
        }
        result.toMap
    }

}