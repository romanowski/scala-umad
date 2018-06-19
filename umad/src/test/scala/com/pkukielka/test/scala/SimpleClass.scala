package com.pkukielka.test.scala

import java.util.{HashSet => JHasjSet}
import java.lang.{Long => JLong}

object Parallel {
  val lock = new Object

  val locks: ThreadLocal[JHasjSet[JLong]] = new ThreadLocal[JHasjSet[JLong]]() {
    override def initialValue(): JHasjSet[JLong] = new JHasjSet[JLong]()
  }

  def sync[T](block: => T) = try {
    locks.get().add(System.identityHashCode(lock).toLong)
    block
  } finally locks.get().remove(System.identityHashCode(lock))
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
