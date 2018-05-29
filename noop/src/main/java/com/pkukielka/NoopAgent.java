package com.pkukielka;

import java.lang.instrument.Instrumentation;

public class NoopAgent {
    public static void premain(String agentArgs, Instrumentation instrumentation) { }
}
