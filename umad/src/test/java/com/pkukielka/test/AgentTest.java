package com.pkukielka.test;

import com.pkukielka.AccessMonitorRewriter;
import org.junit.Before;
import org.junit.Test;

class Synchronizations {
    public static void staticOperation(Runnable op){
       op.run();
    }

    public void operation(Runnable op){
        op.run();
    }
}

class MyTest {
    // Without accessing fields/methods method are marked as safe :)
    private int meaningOfLife = 42;
    private static int universalRule = -1;


    int interestingMethod() {
        return meaningOfLife;
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

    private Thread.UncaughtExceptionHandler uncaughtExceptionHandler = new Thread.UncaughtExceptionHandler() {
        public void uncaughtException(Thread th, Throwable ex) {
            if (ex.getClass() == IllegalThreadStateException.class) failed = true;
        }
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
        startThreads(new Runnable() {
            public void run() {
                t.interestingMethod();
            }
        });
        assert (failed);
    }

    @Test
    public void runInterestingMethodInMultipleThreadsWithManyInstances() throws InterruptedException {
        startThreads(new Runnable() {
            public void run() {
                new MyTest().interestingMethod();
            }
        });
        assert (!failed);
    }

    @Test
    public void runOtherMethodInMultipleThreads() throws InterruptedException {
        final MyTest t = new MyTest();
        startThreads(new Runnable() {
            public void run() {
                t.otherMethod();
            }
        });
        assert (!failed);
    }

    @Test
    public void runStaticMethodInMultipleThreads() throws InterruptedException {
        startThreads(new Runnable() {
            public void run() {
                MyTest.interestingStaticMethod();
            }
        });
        assert (!failed);
    }

    @Test
    public void runInterestingMethodInSingleThread() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            new MyTest().interestingMethod();
        }
    }

    @Test
    public void synchronizedIndicator() throws InterruptedException {
        final MyTest t = new MyTest();
        startThreads(() -> new Synchronizations().operation(() -> t.interestingMethod()));

        assert (!failed);
    }
    @Test
    public void staticSynchronizedIndicator() throws InterruptedException {
        final MyTest t = new MyTest();
        startThreads(() -> Synchronizations.staticOperation(() -> t.interestingMethod()));

        assert (!failed);
    }
}
