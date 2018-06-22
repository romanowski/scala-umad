package com.pkukielka.test;

import com.pkukielka.AccessMonitorRewriter;
import com.pkukielka.test.scala.Parallel$;
import com.pkukielka.test.scala.SynchronizedOps;
import org.junit.Before;
import org.junit.Test;
import com.pkukielka.test.scala.SimpleClass;

class MyTest {
    // Without accessing fields/methods method are marked as safe :)
    private int meaningOfLife = 42;
    private static int universalRule = -1;
    private static int globalVar = 0;
    private static String globalObj = "x";
    private static double globalVarDouble = 0.0;
    private int[] iarr = new int[3];
    private double[] darr = new double[3];
    private long[] larr = new long[3];
    private String[] sarr = new String[3];

    void changeGlobalVar() {
        int x = 777;
        globalVar =  1;
        assert(x == 777);
    }

    void changeGlobalDoubleVar() { globalVarDouble =  globalVarDouble * 2.5; }

    interface Str {
        String getStr();
    }

    void changeGlobalVarCompResult() {
        globalObj =  ((Str) () -> "works").getStr();
    }

    void writeToIntArray() {
        int i = 2;
        iarr[0] = i + 5;
        assert(iarr[0] == 7);
    }

    void writeToDoubleArray() {
        darr[0] = 1.0;
        darr[1] = 2.0;
        darr[2] = darr[0] + darr[1];
        assert(darr[2] == 3.0);
    }

    void writeToLongArray() {
        larr[0] = 2L;
        larr[1] = 7L;
        larr[2] = larr[0] * larr[1];
        assert(larr[2] == 14L);
    }

    void writeToStringArray() {
        sarr[0] = "X";
        sarr[1] = "Y";
        sarr[2] = sarr[0] + sarr[1];
        assert("XY".equals(sarr[2]));
    }

    int interestingMethod() {
        int [] arr2 = new int[5];
        arr2[1] = 4;
        return meaningOfLife = 44  + arr2[1];
    }

    static int interestingStaticMethod() {
        return universalRule;
    }

    int otherMethod() {
        return meaningOfLife / 2;
    }
}

public class AgentTest {
    private boolean failed = false;

    private Thread.UncaughtExceptionHandler uncaughtExceptionHandler = (th, ex) -> {
        if (ex.getClass() == IllegalThreadStateException.class) failed = true;
    };

    private void startThreads(Runnable r) throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            Thread t = new Thread(r);
            t.setUncaughtExceptionHandler(uncaughtExceptionHandler);
            t.start();
            t.join();
        }
    }

    @Before
    public void setUp() {
        AccessMonitorRewriter.clearState();
        failed = false;
    }


    @Test
    public void runInterestingMethodInMultipleThreadsWithSingleInstance() throws InterruptedException {
        final MyTest t = new MyTest();
        startThreads(t::interestingMethod);
        assert (failed);
    }

    @Test
    public void runInterestingMethodInMultipleThreadsWithManyInstances() throws InterruptedException {
        startThreads(() -> new MyTest().interestingMethod());
        assert (!failed);
    }

    @Test
    public void runOtherMethodInMultipleThreads() throws InterruptedException {
        final MyTest t = new MyTest();
        startThreads(t::otherMethod);
        assert (!failed);
    }

    @Test
    public void runStaticMethodInMultipleThreads() throws InterruptedException {
        startThreads(MyTest::interestingStaticMethod);
        assert (!failed);
    }

    @Test
    public void runInterestingMethodInSingleThread() {
        for (int i = 0; i < 3; i++) {
            new MyTest().interestingMethod();
        }
        assert (!failed);
    }

    @Test
    public void synchronizedIndicator() throws InterruptedException {
        final MyTest t = new MyTest();
        startThreads(() -> Parallel$.MODULE$.sync(t::interestingMethod));

        assert (!failed);
    }

    @Test
    public void arrayIntWrite() throws InterruptedException {
        final MyTest t = new MyTest();
        startThreads(t::writeToIntArray);

        assert (failed);
    }

    @Test
    public void arrayDoubleWrite() throws InterruptedException {
        final MyTest t = new MyTest();
        startThreads(t::writeToDoubleArray);

        assert (failed);
    }

    @Test
    public void arrayLongWrite() throws InterruptedException {
        final MyTest t = new MyTest();
        startThreads(t::writeToLongArray);

        assert (failed);
    }

    @Test
    public void arrayStringWrite() throws InterruptedException {
        final MyTest t = new MyTest();
        startThreads(t::writeToStringArray);

        assert (failed);
    }

    @Test
    public void changeGlobalVar() throws InterruptedException {
        final MyTest t = new MyTest();
        startThreads(t::changeGlobalVar);

        assert (failed);
    }

    @Test
    public void changeGlobalVarCompResult() throws InterruptedException {
        final MyTest t = new MyTest();
        startThreads(t::changeGlobalVarCompResult);

        assert (failed);
    }

    @Test
    public void changeGlobalDoubleVar() throws InterruptedException {
        final MyTest t = new MyTest();
        startThreads(t::changeGlobalDoubleVar);

        assert (failed);
    }

    @Test
    public void synchronizedIndicatorWithTrait() throws InterruptedException {
        final SimpleClass t = new SimpleClass();
        startThreads(t::setSynchronizedVar);

        assert (!failed);
    }

    @Test
    public void threadLocalGetter() throws InterruptedException {
        final SimpleClass t = new SimpleClass();
        startThreads(t::testThreadLocal);

        assert (!failed);
    }

    @Test
    public void threadVar() throws InterruptedException {
        final SimpleClass t = new SimpleClass();
        startThreads(t::testVar);

        assert (failed);
    }

}
