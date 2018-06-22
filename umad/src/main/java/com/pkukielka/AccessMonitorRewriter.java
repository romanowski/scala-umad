package com.pkukielka;

import com.typesafe.config.Config;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.NotFoundException;
import javassist.bytecode.*;

import java.util.*;
import java.util.regex.Pattern;

class LastAccess {
    long threadId;
    int hashCode;
    StackTraceElement[] stackTrace;
    String threadName;
    Set<Integer> locks;

    LastAccess(long threadId, int hashCode, String threadName, Set<Integer> locks) {
        this.threadId = threadId;
        this.hashCode = hashCode;
        this.stackTrace = null;
        this.threadName = threadName;
        this.locks = locks;
    }
}

public class AccessMonitorRewriter extends MethodRewriter {
    private static final AppConfig conf = AppConfig.get();
    private static final Map<String, LastAccess> writeLocations = new HashMap<>();
    private static final Set<String> alreadyReported = new HashSet<>();

    private static final ThreadLocal<HashSet<Integer>> locks = ThreadLocal.withInitial(HashSet::new);

    private static final int STACK_TRACE_LENGTH = 10;

    private static final int realStackStartIndex = 2;

    AccessMonitorRewriter(Config config) {
        super(config);
    }

    public static void clearState() {
        writeLocations.clear();
        alreadyReported.clear();
    }

    private static boolean isInStackTrace(String ifCalledFrom,StackTraceElement[] stackTrace) {
        Pattern ifCalledFromPattern = Pattern.compile(ifCalledFrom);
        for (StackTraceElement stackTraceElement : stackTrace) {
            if (ifCalledFromPattern.matcher(stackTraceElement.toString()).matches()) {
                return true;
            }
        }
        return false;
    }

    public static void reportWriteToMemory(Object accessedObject, String accessedObjectName, String ifCalledFrom, String fileName, int lineNumber) {
        int hashCode = accessedObject == null ? 0 : System.identityHashCode(accessedObject);

        synchronized (conf) {
            Thread thread = Thread.currentThread();
            String location = fileName + ":" + lineNumber + "," + accessedObjectName;

            if (thread.getName().equals("main")) return;

            LastAccess current = new LastAccess(thread.getId(), hashCode, thread.getName(), new HashSet<>(locks.get()));
            LastAccess last = writeLocations.put(location, current);

            if (last != null &&
                    last.threadId != current.threadId &&
                    last.hashCode == current.hashCode &&
                    Collections.disjoint(current.locks, last.locks)) {
                current.stackTrace = Thread.currentThread().getStackTrace();

                // Optimization. We want stack traces from both workers but we do not want to get it until we know we have violation
                // because it's really slow. So we need 3 hits to reliably get all information.
                if (last.stackTrace == null) return;

                if (!ifCalledFrom.isEmpty() && !isInStackTrace(ifCalledFrom, current.stackTrace)) return;

                if (alreadyReported.add(location)) {
                    String msg = String.format("Object %s accessed from multiple threads:", accessedObjectName);

                    StringBuilder str = new StringBuilder("[WARN] " + msg + "\n");

                    str.append(current.threadName).append(" stack trace:\n");
                    for (int i = realStackStartIndex; i < Math.min(realStackStartIndex + STACK_TRACE_LENGTH, current.stackTrace.length); i++) {
                        str.append("    ").append(current.stackTrace[i].toString()).append("\n");
                    }
                    str.append(last.threadName).append(" stack trace:\n");
                    for (int i = realStackStartIndex; i < Math.min(realStackStartIndex + STACK_TRACE_LENGTH, last.stackTrace.length); i++) {
                        str.append("    ").append(last.stackTrace[i].toString()).append("\n");
                    }

                    String stack = str.toString();

                    if (conf.shouldPrintStackTrace) System.out.println(stack);
                    if (conf.shouldThrowExceptions) throw new IllegalThreadStateException(msg);
                }
            }
        }
    }

    public static void addLockToCurrentThread(Object o) {
        locks.get().add(System.identityHashCode(o));
    }

    public static void removeLockFromCurrentThread(Object o) {
        locks.get().remove(System.identityHashCode(o));
    }


    @Override
    protected void editMethod(CtMethod editableMethod, String ifCalledFrom, String dottedClassName) {
        try {
            CtClass currentClazz = editableMethod.getDeclaringClass();
            ConstPool constPool = currentClazz.getClassFile().getConstPool();

            MethodInfo info = editableMethod.getMethodInfo();
            if (info == null) return;

            CodeAttribute codeAttr = info.getCodeAttribute();
            if (codeAttr == null) return;

            CodeIterator codeIterator = codeAttr.iterator();
            if (codeIterator == null) return;

            codeAttr.insertLocalVar(codeAttr.getMaxLocals(), 4);

            while (codeIterator.hasNext()) {
                int codeIndex = codeIterator.next();
                int opCode = codeIterator.byteAt(codeIndex);
                switch (opCode) {
                    case Opcode.PUTFIELD:
                        insertCallToReporterForField(Opcode.DUP, ifCalledFrom, currentClazz, constPool, info, codeAttr, codeIterator, codeIndex);
                        break;
                    case Opcode.PUTSTATIC:
                        insertCallToReporterForField(Opcode.ACONST_NULL, ifCalledFrom, currentClazz, constPool, info, codeAttr, codeIterator, codeIndex);
                        break;
                    case Opcode.MONITORENTER:
                        insertCallToSingleArgFunction("addLockToCurrentThread", constPool, codeIterator, codeIndex);
                        break;
                    case Opcode.MONITOREXIT:
                        insertCallToSingleArgFunction("removeLockFromCurrentThread", constPool, codeIterator, codeIndex);
                        break;
                    case Opcode.AASTORE:
                    case Opcode.BASTORE:
                    case Opcode.CASTORE:
                    case Opcode.DASTORE:
                    case Opcode.FASTORE:
                    case Opcode.IASTORE:
                    case Opcode.LASTORE:
                    case Opcode.SASTORE:
                        insertCallToReporterForArray(opCode, ifCalledFrom, currentClazz, constPool, info, codeAttr, codeIterator, codeIndex);
                        break;
                }
            }

            codeAttr.computeMaxStack();
        } catch (BadBytecode | NotFoundException e) {
            e.printStackTrace();
        }
    }

    private CtClass getArrayElemClass(ClassPool classPool, int opCode) throws NotFoundException {
        switch(opCode) {
            case Opcode.LASTORE:
                return CtClass.longType;
            case Opcode.FASTORE:
                return CtClass.floatType;
            case Opcode.DASTORE:
                return CtClass.doubleType;
            case Opcode.AASTORE:
                return classPool.get("java.lang.Object");
            case Opcode.IASTORE:
            case Opcode.BASTORE:
            case Opcode.CASTORE:
            case Opcode.SASTORE:
                return CtClass.intType;
            default:
                return null;
        }
    }

    private CtClass getFieldCtClass(CtClass currentClazz, ConstPool constPool, int fieldIndex) throws NotFoundException {
        String tpe = constPool.getFieldrefType(fieldIndex);
        ClassPool classPool = currentClazz.getClassPool();
        return classPool.get(Descriptor.toClassName(tpe));
    }

    private String getFieldFqn(ConstPool constPool, int fieldIndex) {
        String name = constPool.getFieldrefName(fieldIndex);
        String className = constPool.getFieldrefClassName(fieldIndex);
        return className + "." + name;
    }

    private void insertCallToSingleArgFunction(String funcName, ConstPool constPool, CodeIterator codeIterator, int codeIndex) throws BadBytecode {
        Bytecode code = new Bytecode(constPool);
        code.addOpcode(Opcode.DUP);
        code.addInvokestatic("com.pkukielka.AccessMonitorRewriter", funcName, "(Ljava/lang/Object;)V");
        codeIterator.insert(codeIndex, code.get());
    }

    private void insertCallToReporterForArray(int opCode, String ifCalledFrom, CtClass currentClazz, ConstPool constPool, MethodInfo info,
                                      CodeAttribute codeAttr, CodeIterator codeIterator, int codeIndex) throws BadBytecode, NotFoundException {
        CtClass elemClass = getArrayElemClass(currentClazz.getClassPool(), opCode);
        insertCallToReporter(true, Opcode.DUP, "", elemClass, ifCalledFrom, currentClazz, constPool, info, codeAttr, codeIterator, codeIndex);
    }

    private void insertCallToReporterForField(int prepareThisOpcode, String ifCalledFrom, CtClass currentClazz, ConstPool constPool, MethodInfo info,
                                              CodeAttribute codeAttr, CodeIterator codeIterator, int codeIndex) throws BadBytecode, NotFoundException {
        int fieldIndex = codeIterator.byteAt(codeIndex + 2) + (codeIterator.byteAt(codeIndex + 1) << 8);
        CtClass elemClass = getFieldCtClass(currentClazz, constPool, fieldIndex);
        String fqn = getFieldFqn(constPool, fieldIndex);
        insertCallToReporter(false, prepareThisOpcode, fqn, elemClass, ifCalledFrom, currentClazz, constPool, info, codeAttr, codeIterator, codeIndex);
    }

    private void insertCallToReporter(boolean isArray, int prepareThisOpcode,  String fqn, CtClass elemClass,
                                      String ifCalledFrom, CtClass currentClazz, ConstPool constPool, MethodInfo info,
                                      CodeAttribute codeAttr, CodeIterator codeIterator, int codeIndex) throws BadBytecode {
        Bytecode code = new Bytecode(constPool);
        int maxLocals = codeAttr.getMaxLocals();

        code.addStore(maxLocals - 4, elemClass);
        if (isArray) code.addStore(maxLocals - 2, CtClass.intType);

        code.addOpcode(prepareThisOpcode);
        code.addLdc(fqn);
        code.addLdc(ifCalledFrom);
        code.addLdc(currentClazz.getClassFile().getSourceFile());
        code.addIconst(info.getLineNumber(codeIndex));
        code.addInvokestatic("com.pkukielka.AccessMonitorRewriter", "reportWriteToMemory", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V");

        if (isArray)  code.addLoad(maxLocals - 2, CtClass.intType);
        code.addLoad(maxLocals - 4, elemClass);

        codeIterator.insert(codeIndex, code.get());
    }
}
