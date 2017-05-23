package better.files

import java.util.concurrent.atomic.AtomicBoolean

import scala.util.{Failure, Success, Try}

/**
  * A typeclass to denote a disposable resource
  * @tparam A
  */
trait Disposable[-A]  {
  def dispose(resource: A): Unit

  def disposeSilently(resource: A): Unit = {
    val _ = Try(dispose(resource))
  }
}

object Disposable {
  def apply[A](disposeMethod: A => Any): Disposable[A] = new Disposable[A] {
    override def dispose(resource: A) = {
      val _ = disposeMethod(resource)
    }
  }

  implicit val closableDisposer: Disposable[Closeable] =
    Disposable(_.close())

  val fileDisposer: Disposable[File] =
    Disposable(_.delete(swallowIOExceptions = true))
}

class ManagedResource[A](resource: A)(implicit disposer: Disposable[A]) {
  private[this] val isDisposed = new AtomicBoolean(false)
  private[this] def disposeOnce() = if (!isDisposed.getAndSet(true)) disposer.disposeSilently(resource)

  def foreach[U](f: A => U): Unit = {
    val _ = map(f)
  }

  def map[B](f: A => B): B = {
    val result = Try(f(resource))
    disposeOnce()
    result.get
  }

  /**
    * This handles lazy operations (e.g. Iterators)
    * for which resource needs to be disposed only after iteration is done
    *
    * @param f
    * @tparam B
    * @return
    */
  def flatMap[B](f: A => Iterator[B]): Iterator[B] = {
    new Iterator[B] {
      val proxy = f(resource)
      override def hasNext = {
        val result = Try(proxy.hasNext)
        result match {
          case Failure(_) | Success(false) => disposeOnce()
          case _ =>
        }
        result.get
      }
      override def next() = proxy.next()
    }
  }
}
