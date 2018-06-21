package com.pkukielka;

import com.typesafe.config.Config;
import javassist.CannotCompileException;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.bytecode.*;

import java.util.*;
import java.util.regex.Pattern;

class LastAccess {
    long timestamp;
    long threadId;
    int hashCode;
    StackTraceElement[] stackTrace;
    String threadName;
    Set<Long> locks;

    LastAccess(long timestamp, long threadId, int hashCode, String threadName, Set<Long> locks) {
        this.timestamp = timestamp;
        this.threadId = threadId;
        this.hashCode = hashCode;
        this.stackTrace = null;
        this.threadName = threadName;
        this.locks = locks;
    }
}

public class AccessMonitorRewriter extends MethodRewriter {
    private static final AppConfig conf = AppConfig.get();
    private static final Map<String, LastAccess> methodCalls = new HashMap<>();
    private static final Set<String> alreadyReported = new HashSet<>();
    private static String locks;

    private static final int STACK_TRACE_LENGTH = 40;

    private static final int realStackStartIndex = 2;

    AccessMonitorRewriter(Config config) {
        super(config);
        locks = config.getString("locks");
    }

    public static void clearState() {
        methodCalls.clear();
        alreadyReported.clear();
    }

    public static void logUnsafeMethodCalls(String methodName, String ifCalledFrom, int hashCode, Object locks) {
        synchronized (conf) {
            Thread thread = Thread.currentThread();
            Long currentTimestamp = System.currentTimeMillis();

            if (thread.getName().equals("main")) return;

            LastAccess current = new LastAccess(currentTimestamp, thread.getId(), hashCode, thread.getName(), new HashSet<>((HashSet<Long>)locks));
            LastAccess last = methodCalls.put(methodName, current);

            if (last != null &&
                    last.threadId != current.threadId &&
                    last.hashCode == current.hashCode &&
                    current.timestamp - last.timestamp <= conf.intervalMs &&
                    Collections.disjoint(current.locks, last.locks)) {
                current.stackTrace = Thread.currentThread().getStackTrace();

                // Optimization. We want stack traces from both workers but we do not want to get it until we know we have violation
                // because it's really slow. So we need 3 hits to reliably get all information.
                if (last.stackTrace == null) return;

                String calledFrom = current.stackTrace[realStackStartIndex + 1].toString();

                Pattern ifCalledFromPattern = Pattern.compile(ifCalledFrom);
                methodName = (ifCalledFrom.equals("null")) ? methodName :
                        (ifCalledFromPattern.matcher(calledFrom).matches() ? calledFrom : null);

                if (methodName != null && alreadyReported.add(methodName)) {
                    String msg = String.format("Method accessed from multiple threads (%s, %s): %s",
                            last.threadName, current.threadName, methodName);

                    StringBuilder str = new StringBuilder("[WARN] " + msg + "\n");
                    boolean areStackTraceDifferent = !Arrays.equals(current.stackTrace, last.stackTrace);

                    if (areStackTraceDifferent) str.append(current.threadName + " stack trace:\n");
                    for (int i = realStackStartIndex; i < Math.min(realStackStartIndex + STACK_TRACE_LENGTH, current.stackTrace.length); i++) {
                        str.append("    ").append(current.stackTrace[i].toString()).append("\n");
                    }
                    if (areStackTraceDifferent) {
                        str.append(last.threadName + " stack trace:\n");
                        for (int i = realStackStartIndex; i < Math.min(realStackStartIndex + STACK_TRACE_LENGTH, last.stackTrace.length); i++) {
                            str.append("    ").append(last.stackTrace[i].toString()).append("\n");
                        }
                    }

                    String stack = str.toString();

                    if (conf.shouldPrintStackTrace) System.out.println(stack);
                    if (conf.shouldThrowExceptions) throw new IllegalThreadStateException(msg);
                }
            }
        }
    }

    @Override
    protected void editMethod(CtMethod editableMethod, String ifCalledFrom, String dottedClassName) throws CannotCompileException {
        if (isUnsafe(editableMethod)) {
            String methodName = editableMethod.getLongName();
            String callLogUnsafeMethodCalls = "com.pkukielka.AccessMonitorRewriter.logUnsafeMethodCalls" +
                    "(\"%s\", \"%s\", System.identityHashCode(this), %s.get());";

            editableMethod.insertBefore(String.format(callLogUnsafeMethodCalls, methodName, ifCalledFrom, locks));
        } else if (conf.verbose) {
            String msg = "Method %s was marked as safe.";
            System.out.println(String.format(msg, editableMethod.getLongName()));
        }
    }


    private boolean isUnsafe(CtMethod editableMethod) {
        try {
            CtClass currentClazz = editableMethod.getDeclaringClass();
            ConstPool constPool = currentClazz.getClassFile().getConstPool();

            MethodInfo info = editableMethod.getMethodInfo();
            if (info == null) return false;

            CodeAttribute codeAttr = info.getCodeAttribute();
            if (codeAttr == null) return false;

            CodeIterator codeIterator = codeAttr.iterator();
            if (codeIterator == null) return false;

            while (codeIterator.hasNext()) {
                int codeIndex = codeIterator.next();
                int opCode = codeIterator.byteAt(codeIndex);

                switch (opCode) {
                    case Opcode.PUTFIELD:
                    case Opcode.PUTSTATIC:
                        return true;
                    case Opcode.GETFIELD:
                    case Opcode.GETSTATIC:
                        int fieldIndex = codeIterator.byteAt(codeIndex + 2) +
                                (codeIterator.byteAt(codeIndex + 1) << 8);
                        String fieldType = constPool.getFieldrefType(fieldIndex);
                        if (fieldType.startsWith("[")) return  true;
                }
            }
            return false;
        } catch (BadBytecode e) {
            e.printStackTrace();
            return true;
        }
    }
}
