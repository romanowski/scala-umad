package com.pkukielka.test.scala

object Parallel {
  def sync[T](block: => T) = block
}

trait SynchronizedOps {
  var _v: Int = 1
  def v_=(value: Int): Unit = Parallel.sync { _v = value }
  def v: Int = Parallel.sync(_v)
}

class SimpleClass extends SynchronizedOps {
  private val _plainThreadLocal = new ThreadLocal[String]()

  def setSynchronizedVar() = v = 3

  def plainThreadLocal: String = _plainThreadLocal.get()
  def plainThreadLocal_=(newValue: String): Unit = _plainThreadLocal.set(newValue)

  var plainVar: String = ""

  def testThreadLocal(): Unit = plainThreadLocal = "ala"

  def testVar(): Unit = {
    plainVar = "ala"
  }
}
