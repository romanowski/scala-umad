package com.pkukielka.test

import com.pkukielka.AccessMonitorRewriter
import com.pkukielka.test.scala.SimpleClass
import org.junit.Assert._
import org.junit.Test

class SafeMethodTest {
  new SimpleClass()

  @Test
  def checkSafeMethods(): Unit = {
    import collection.JavaConverters._
    val methods = AccessMonitorRewriter.getMethodsMarkedAsSafe.asScala.toSet

    def checkMethod(name: String,
                    inClazz: String = "SimpleClass",
                    tpe: String = "java.lang.String",
                    safe: Boolean = true): Unit = {
      val sig = s"com.pkukielka.test.scala.$inClazz.${name}_$$eq($tpe)"
      def msg = s"Method $sig should ${if(safe)"" else "not "}be safe. Full list: ${methods.mkString("\n")}"
      val present = methods.contains(sig)
      assertTrue(msg, ! (present ^ safe))

    }
    checkMethod("plainThreadLocal")
    checkMethod("plainVar", safe = false)
  }
}