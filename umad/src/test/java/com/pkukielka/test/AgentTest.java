package com.pkukielka.test;

import com.pkukielka.AccessMonitorRewriter;
import com.pkukielka.test.scala.Parallel$;
import org.junit.Before;
import org.junit.Test;
import com.pkukielka.test.scala.SimpleClass;

class BaseForMyTest {
    transient protected int parentTransientInt = 1;
}

class MyTest extends BaseForMyTest {
    // Without accessing fields/methods method are marked as safe :)
    private int meaningOfLife = 42;
    private double d = 1.2;
    private static int universalRule = -1;
    private static int globalVar = 0;
    private static String globalObj = "x";
    private static double globalVarDouble = 0.0;
    private int[] iarr = new int[3];
    private double[] darr = new double[3];
    private long[] larr = new long[3];
    private String[] sarr = new String[3];
    transient private int transientInt = 1;
    volatile private int volatileInt = 5;


    void changeDouble() {
        d = 3.4;
    }

    void changeMeaningOfLife() {
        meaningOfLife = 44;
    }

    void changeGlobalVar() {
        int x = 777;
        globalVar =  1;
        assert(x == 777);
    }

    void changeTransientInt() {
        transientInt = transientInt * meaningOfLife;
        volatileInt = volatileInt * globalVar;
    }

    void changeParentTransientInt() {
        parentTransientInt = parentTransientInt * meaningOfLife;
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

    public synchronized int synchronizedMethod(){
        meaningOfLife = universalRule / 3;
        return meaningOfLife;
    }

    public static void resetState(){}
}

public class AgentTest {
    private boolean hasFailed() {
        return AccessMonitorRewriter.warnings.size() > 0;
    }

    private int violationsCount() {
        return AccessMonitorRewriter.warnings.size();
    }

    private void startThreads(Runnable r, int n) throws InterruptedException {
        for (int i = 0; i < n; i++) {
            Thread t = new Thread(r);
            t.start();
            t.join();
        }
    }

    private void startThreads(Runnable r) throws InterruptedException {
        startThreads(r, 3);
    }

    @Before
    public void setUp() {
        AccessMonitorRewriter.clearState();
    }


    @Test
    public void runInterestingMethodInMultipleThreadsWithSingleInstance() throws InterruptedException {
        final MyTest t = new MyTest();
        startThreads(t::interestingMethod);
    }

    @Test
    public void runInterestingMethodInMultipleThreadsWithManyInstances() throws InterruptedException {
        startThreads(() -> new MyTest().interestingMethod());
        assert (!hasFailed());
    }

    @Test
    public void runOtherMethodInMultipleThreads() throws InterruptedException {
        final MyTest t = new MyTest();
        startThreads(t::otherMethod);
        assert (!hasFailed());
    }

    @Test
    public void runStaticMethodInMultipleThreads() throws InterruptedException {
        startThreads(MyTest::interestingStaticMethod);
        assert (!hasFailed());
    }

    @Test
    public void runInterestingMethodInSingleThread() {
        for (int i = 0; i < 3; i++) {
            new MyTest().interestingMethod();
        }
        assert (!hasFailed());
    }

    @Test
    public void synchronizedIndicator() throws InterruptedException {
        final MyTest t = new MyTest();
        startThreads(() -> Parallel$.MODULE$.sync(t::interestingMethod));

        assert (!hasFailed());
    }

    @Test
    public void arrayIntWrite() throws InterruptedException {
        final MyTest t = new MyTest();
        startThreads(t::writeToIntArray);

        assert (hasFailed());
    }

    @Test
    public void arrayDoubleWrite() throws InterruptedException {
        final MyTest t = new MyTest();
        startThreads(t::writeToDoubleArray);

        assert (hasFailed());
    }

    @Test
    public void arrayLongWrite() throws InterruptedException {
        final MyTest t = new MyTest();
        startThreads(t::writeToLongArray);

        assert (hasFailed());
    }

    @Test
    public void arrayStringWrite() throws InterruptedException {
        final MyTest t = new MyTest();
        startThreads(t::writeToStringArray);

        assert (hasFailed());
    }

    @Test
    public void changeGlobalVar() throws InterruptedException {
        final MyTest t = new MyTest();
        startThreads(t::changeGlobalVar);

        assert (hasFailed());
    }

    @Test
    public void testMultipleVarsAccessFromTheSameObject() throws InterruptedException {
        final MyTest t = new MyTest();
        startThreads(t::changeMeaningOfLife, 1);
        startThreads(t::changeDouble, 1);

        assert (!hasFailed());
    }

    @Test
    public void changeGlobalVarCompResult() throws InterruptedException {
        final MyTest t = new MyTest();
        startThreads(t::changeGlobalVarCompResult);

        assert (hasFailed());
    }

    @Test
    public void changeGlobalDoubleVar() throws InterruptedException {
        final MyTest t = new MyTest();
        startThreads(t::changeGlobalDoubleVar);

        assert (hasFailed());
    }

    @Test
    public void changeTransientInt() throws InterruptedException {
        final MyTest t = new MyTest();
        startThreads(t::changeTransientInt);

        assert (!hasFailed());
    }

    @Test
    public void changeParentTransientInt() throws InterruptedException {
        final MyTest t = new MyTest();
        startThreads(t::changeParentTransientInt);

        assert (!hasFailed());
    }

    @Test
    public void synchronizedIndicatorWithTrait() throws InterruptedException {
        final SimpleClass t = new SimpleClass();
        startThreads(t::setSynchronizedVar);

        assert (!hasFailed());
    }

    @Test
    public void threadLocalGetter() throws InterruptedException {
        final SimpleClass t = new SimpleClass();
        startThreads(t::testThreadLocal);

        assert (!hasFailed());
    }

    @Test
    public void threadVar() throws InterruptedException {
        final SimpleClass t = new SimpleClass();
        startThreads(t::testVar);

        assert (hasFailed());
    }

    @Test
    public void testSynchronizationWithDifferentLocks() throws InterruptedException {
        final SimpleClass t = new SimpleClass();
        startThreads(t::testSynchronizationWithDifferentLocks);

        assert (hasFailed());
    }

    @Test
    public void testNestedSync() throws InterruptedException {
        final SimpleClass t = new SimpleClass();
        startThreads(t::nestedSynchronization);

        assert (!hasFailed());
    }

    @Test
    public void testDoubleSynchronization() throws InterruptedException {
        final SimpleClass t = new SimpleClass();
        startThreads(t::doubleSynchronization2);
        startThreads(t::doubleSynchronization1);

        assert (!hasFailed());
    }

    @Test
    public void testSynchronizedMethod() throws InterruptedException {
        final MyTest t = new MyTest();
        startThreads(t::synchronizedMethod);

        assert (!hasFailed());
    }
}