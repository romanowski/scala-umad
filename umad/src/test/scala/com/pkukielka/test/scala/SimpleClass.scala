package com.pkukielka.test.scala

class SimpleClass {
  private val _plainThreadLocal = new ThreadLocal[String]()

  def plainThreadLocal: String = _plainThreadLocal.get()
  def plainThreadLocal_=(newValue: String): Unit = _plainThreadLocal.set(newValue)

  var plainVar: String = ""

  def testThreadLocal(): Unit = plainThreadLocal = "ala"

  def testVar(): Unit = plainVar = "ala"
}
