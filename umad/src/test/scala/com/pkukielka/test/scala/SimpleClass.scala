package com.pkukielka.test.scala


import com.pkukielka.test.scala.Parallel.lock
import com.pkukielka.test.scala.Parallel.lock2


object Parallel {
  val lock = new Object
  val lock2 = new Object
  def sync[T](block: => T) = lock.synchronized(block)
}

trait SynchronizedOps {
  var _v: Int = 1
  def v_=(value: Int): Unit =  lock.synchronized { _v = value }
  def v: Int =  lock.synchronized(_v)
}

class SimpleClass extends SynchronizedOps {
  var i = 1

  private val _plainThreadLocal = new ThreadLocal[String]()

  def setSynchronizedVar() = v = 3

  def plainThreadLocal: String = _plainThreadLocal.get()
  def plainThreadLocal_=(newValue: String): Unit = _plainThreadLocal.set(newValue)

  var plainVar: String = ""

  def testThreadLocal(): Unit = plainThreadLocal = "ala"

  def testSynchronizationWithDifferentLocks(): Unit = {
    lock.synchronized { i = 1 }
    lock2.synchronized { i = 3 }
  }

  def testVar(): Unit = {
    plainVar = "ala"
  }

  private var nestedVar1 = 2
  private var nestedVar2 = 7
  private def innerNestedSynchronization() = Parallel.sync {
    nestedVar1 = nestedVar2 * nestedVar1
  }

  def nestedSynchronization() = Parallel.sync {
    innerNestedSynchronization()
    nestedVar2 = nestedVar2 * nestedVar1
  }


  private var doubleSynchronizationState = 4

  def doubleSynchronization1() = Parallel.lock2.synchronized {
    Parallel.sync {
      doubleSynchronizationState = nestedVar1 * nestedVar2
    }
  }

  def doubleSynchronization2() = Parallel.lock2.synchronized {
    doubleSynchronizationState = nestedVar1 / nestedVar2
  }
}
