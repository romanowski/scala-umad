package com.pkukielka;

import javassist.*;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

public class UmadTransformer implements ClassFileTransformer {
    private final MethodRewriter monitorAccess;
    private final MethodRewriter chaos;
    UmadTransformer(final Instrumentation instrumentation) {
        monitorAccess = new AccessMonitorRewriter(AppConfig.get().getMonitorConfig());
        chaos = new ChaosRewriter(AppConfig.get().getChaosConfig());

        instrumentation.addTransformer(this, true);
        System.out.println("[Info] TracingTransformer active");
    }

    public byte[] transform(final ClassLoader loader, final String className, final Class classBeingRedefined,
                            final ProtectionDomain protectionDomain, final byte[] classfileBuffer) throws IllegalClassFormatException {
        byte[] byteCode = classfileBuffer;
        final String classNameDotted = className.replaceAll("/", ".");
        boolean monitorApply = monitorAccess.shouldTransformClass(classNameDotted);
        boolean chaosApply = chaos.shouldTransformClass(classNameDotted);

        if (monitorApply || chaosApply) {
            try {
                final ClassPool classpool = ClassPool.getDefault();
                final ClassPath loaderClassPath = new LoaderClassPath(loader);
                final ClassPath byteArrayClassPath = new ByteArrayClassPath(classNameDotted, byteCode);

                // We add the loaderClassPath so that the classpool can find the dependencies needed when it needs to recompile the class
                classpool.appendClassPath(loaderClassPath);
                // This class has not yet actually been loaded by any classloader, so we must add the class directly so it can be found by the classpool.
                classpool.insertClassPath(byteArrayClassPath);

                final CtClass editableClass = classpool.get(classNameDotted);
                final CtMethod declaredMethods[] = editableClass.getDeclaredMethods();
                for (final CtMethod editableMethod : declaredMethods) {
                    if (monitorApply) {
                        monitorAccess.applyOnMethod(editableMethod, classNameDotted);
                    }
                    if (chaosApply) {
                        chaos.applyOnMethod(editableMethod, classNameDotted);
                    }
                }

                byteCode = editableClass.toBytecode();
                editableClass.detach();

                // These appear to only be needed during rewriting
                // If we don't remove, the list just keeps growing as we rewrite more classes
                // or transform the same class again
                classpool.removeClassPath(loaderClassPath);
                classpool.removeClassPath(byteArrayClassPath);

                if (AppConfig.get().verbose) {
                    System.out.println("[Info] Transformed " + classNameDotted);
                }
            } catch (Exception ex) {
                System.err.println("[Error] Unable to transform: " + classNameDotted);
                ex.printStackTrace();
            }
        }


        return byteCode;
    }
}