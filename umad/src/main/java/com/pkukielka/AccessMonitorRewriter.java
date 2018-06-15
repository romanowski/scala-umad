package com.pkukielka;

import com.typesafe.config.Config;
import javassist.*;
import javassist.bytecode.*;

import java.util.*;
import java.util.regex.Pattern;

class LastAccess {
    long timestamp;
    long threadId;
    int hashCode;
    String stackTrace;
    String threadName;

    LastAccess(long timestamp, long threadId, int hashCode, String threadName) {
        this.timestamp = timestamp;
        this.threadId = threadId;
        this.hashCode = hashCode;
        this.stackTrace = null;
        this.threadName = threadName;
    }
}

public class AccessMonitorRewriter extends MethodRewriter {
    private static final AppConfig conf = AppConfig.get();
    private static final Map<String, LastAccess> methodCalls = new HashMap<String, LastAccess>();
    private static final Set<String> alreadyReported = new HashSet<String>();

    private final Set<String> safeMethodTypes = new HashSet<String>();
    private final Set<String> safeMethods = new HashSet<String>();
    private final Set<String> safeFieldTypes = new HashSet<String>();

    private static final Set<String> synchronizeIndicators = new HashSet<String>();

    private static final int STACK_TRACE_LENGTH = 5;

    private static final int realStackStartIndex = 2;

    private static int safetyChecksRecursionLevel = 1;

    public static void clearState() {
        methodCalls.clear();
        alreadyReported.clear();
    }

    public static void logUnsafeMethodCalls(String methodName, String ifCalledFrom, int hashCode) {
        synchronized (conf) {
            Thread thread = Thread.currentThread();
            Long currentTimestamp = System.currentTimeMillis();

            LastAccess current = new LastAccess(currentTimestamp, thread.getId(), hashCode, thread.getName());
            LastAccess last = methodCalls.put(methodName, current);

            if (last != null &&
                    last.threadId != current.threadId &&
                    last.hashCode == current.hashCode &&
                    current.timestamp - last.timestamp <= conf.intervalMs) {
                StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();

                for (StackTraceElement elem : stackTrace) {
                    String fqn = elem.getClassName() + "." + elem.getMethodName();

                    if (synchronizeIndicators.contains(fqn)) return;
                }

                String calledFrom = stackTrace[realStackStartIndex + 1].toString();

                Pattern ifCalledFromPattern = Pattern.compile(ifCalledFrom);
                methodName = (ifCalledFrom.equals("null")) ? methodName :
                        (ifCalledFromPattern.matcher(calledFrom).matches() ? calledFrom : null);

                if (methodName != null && alreadyReported.add(methodName)) {
                    String msg = String.format("Method accessed from multiple threads (%s, %s): %s",
                            last.threadName, current.threadName, methodName);

                    StringBuilder str = new StringBuilder("[WARN] " + msg + "\n");
                    for (int i = realStackStartIndex; i < Math.min(realStackStartIndex + STACK_TRACE_LENGTH, stackTrace.length); i++) {
                        str.append("    ").append(stackTrace[i].toString()).append("\n");
                    }
                    String stack = str.toString();
                    current.stackTrace = stack;

                    if (conf.shouldPrintStackTrace) System.out.println(stack);
                    if (conf.shouldThrowExceptions) throw new IllegalThreadStateException(msg);
                }
            }
        }
    }

    public AccessMonitorRewriter(Config config) {
        super(config);

        if (config.hasPath("safetyChecksRecursionLevel"))
            safetyChecksRecursionLevel =  config.getInt("safetyChecksRecursionLevel");

        if (isEnabled()) {
            String msg = "Analyzing method access with shouldThrowExceptions=%s and shouldPrintStackTrace=%s";
            System.out.println(String.format(msg, conf.shouldThrowExceptions, conf.shouldPrintStackTrace));

            for (Config safeIndicator : config.getConfigList("safeIndicators")) {
                final String clazz = safeIndicator.getString("clazz");
                final String jvmType = "L" + clazz.replace('.', '/') + ";";
                safeFieldTypes.add(jvmType);
                safeMethodTypes.add("()" + jvmType); // Getter of thread local
                for (Object methodTpe : safeIndicator.getAnyRefList("methods"))
                    safeMethods.add(clazz + "." + methodTpe);
            }

            synchronizeIndicators.addAll(config.getStringList("synchronizeIndicators"));
        }
    }


    private final String callLogUnsafeMethodCalls = "com.pkukielka.AccessMonitorRewriter.logUnsafeMethodCalls" +
            "(\"%s\", \"%s\", System.identityHashCode(this));";

    @Override
    protected void editMethod(CtMethod editableMethod, String ifCalledFrom, String dottedName) throws CannotCompileException {
        String methodName = editableMethod.getLongName();
        editableMethod.insertBefore(String.format(callLogUnsafeMethodCalls, methodName, ifCalledFrom));
    }

    @Override
    public void applyOnMethod(CtMethod editableMethod, String dottedClassName) throws CannotCompileException {
        if (!isSafe(editableMethod, safetyChecksRecursionLevel)) super.applyOnMethod(editableMethod, dottedClassName);
        else if (conf.verbose){
            String msg = "Method %s was marked as safe.";
            System.out.println(String.format(msg, editableMethod.getLongName()));
        }
    }

    private static Set<Integer> methodOpcodes = new HashSet<Integer>(Arrays.asList(
            Opcode.INVOKEVIRTUAL,
            Opcode.INVOKESPECIAL,
            Opcode.INVOKEINTERFACE,
            Opcode.INVOKESTATIC
    ));

    private boolean isSafeMethod(ConstPool pool, int index) {
        String className = pool.getMethodrefClassName(index);
        String name = pool.getMethodrefName(index);
        String tpe = pool.getMethodrefType(index);
        String fqn = className + "." + name + tpe;

        return safeMethods.contains(fqn);
    }

    private CtMethod getMethodIfExist(ConstPool constPool, int methodIndex)  {
        try {
            String className = constPool.getMethodrefClassName(methodIndex);
            String methodName = constPool.getMethodrefName(methodIndex);
            if (methodName.endsWith("$")) methodName = methodName.substring(0, methodName.length() - 1);

            ClassPool classpool = ClassPool.getDefault();
            CtClass editableClass = classpool.get(className);
            CtMethod[] declaredMethods = editableClass.getDeclaredMethods(methodName);

            return declaredMethods.length == 1 ? declaredMethods[0] : null;
        } catch (NotFoundException e) {
            return null;
        }
    }

    private boolean isSafe(CtMethod editableMethod, int safetyChecksRecursionLevel) {
        try {
            CtClass currentClazz = editableMethod.getDeclaringClass();
            ConstPool constPool = currentClazz.getClassFile().getConstPool();

            MethodInfo info = editableMethod.getMethodInfo();
            if (info == null) return false;

            CodeAttribute codeAttr = info.getCodeAttribute();
            if (codeAttr == null) return false;

            CodeIterator codeItertator = codeAttr.iterator();
            if (codeItertator == null) return false;

            while (codeItertator.hasNext()) {
                int codeIndex = codeItertator.next();
                int opCode = codeItertator.byteAt(codeIndex);
                if (methodOpcodes.contains(opCode)) {
                    int methodIndex = codeItertator.byteAt(codeIndex + 2) +
                            (codeItertator.byteAt(codeIndex + 1) << 8);

                    if (!isSafeMethod(constPool, methodIndex)) {
                        if (!safeMethodTypes.contains(constPool.getMethodrefType(methodIndex))) {
                            if (safetyChecksRecursionLevel > 0) {
                                CtMethod method = getMethodIfExist(constPool, methodIndex);
                                return method != null && isSafe(method, safetyChecksRecursionLevel - 1);
                            }

                            return false;
                        }
                    }
                } else switch (opCode) {
                    case Opcode.PUTFIELD:
                    case Opcode.PUTSTATIC:
                        // PUT* modify state
                        return false;
                    case Opcode.GETFIELD:
                    case Opcode.GETSTATIC:
                        // We can read field with safe class
                        int fieldIndex = codeItertator.byteAt(codeIndex + 2) +
                                (codeItertator.byteAt(codeIndex + 1) << 8);
                        if (!safeFieldTypes.contains(constPool.getFieldrefType(fieldIndex)))
                            return false;
                }
            }
            return true;
        } catch (BadBytecode e) {
            e.printStackTrace();
        }
        return false;
    }
}
