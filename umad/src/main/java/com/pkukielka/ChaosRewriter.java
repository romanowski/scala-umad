package com.pkukielka;

import com.typesafe.config.Config;
import javassist.CannotCompileException;
import javassist.CtMethod;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;


public class ChaosRewriter extends MethodRewriter {

    public ChaosRewriter(Config config){
        super(config);
    }

    @Override
    protected void editMethod(final CtMethod editableMethod, String ifCalledFrom) throws CannotCompileException {
//        String methodName = editableMethod.getLongName();
//        editableMethod.insertBefore(
//                String.format("com.pkukielka.AccessMonitorRewriter.logUnsafeMethodCalls(\"%s\", \"%s\", System.identityHashCode(this));",
//                        methodName, ifCalledFrom));
    }
}
