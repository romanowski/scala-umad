package com.pkukielka;

import java.lang.instrument.Instrumentation;

public class UmadAgent {
    public static void premain(String agentArgs, Instrumentation instrumentation) {
        System.out.println("Starting UmadAgent");
        new UmadTransformer(instrumentation);
    }
}
