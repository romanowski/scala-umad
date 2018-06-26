package com.pkukielka;

import com.typesafe.config.Config;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.NotFoundException;
import javassist.bytecode.*;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.regex.Pattern;

class LastAccess {
    long threadId;
    String accessedObjectName;
    WeakReference<Object> ths;
    StackTraceElement[] stackTrace;
    String threadName;
    Set<Set<Integer>> locks = new HashSet<>();

    int identityKey() {
        return (System.identityHashCode(ths.get()) + accessedObjectName).hashCode();
    }

    void addLocks(Set<Integer> currentLocks) {
        if (!currentLocks.isEmpty()) locks.add(new HashSet<>(currentLocks));
    }

    LastAccess(long threadId, String accessedObjectName, WeakReference<Object> ths, String threadName, Set<Integer> locks) {
        this.threadId = threadId;
        this.accessedObjectName = accessedObjectName;
        this.ths = ths;
        this.stackTrace = null;
        this.threadName = threadName;
        addLocks(locks);
    }
}

public class AccessMonitorRewriter extends MethodRewriter {
    private static final AppConfig conf = AppConfig.get();
    private static final Map<Integer, LastAccess> writeLocations = new HashMap<>();
    private static final Set<String> alreadyReported = new HashSet<>();
    private static final Pattern ifCalledFromPattern =
            Pattern.compile(conf.getMonitorConfig().getString("ifCalledFrom"));

    private static final ThreadLocal<HashSet<Integer>> locks = ThreadLocal.withInitial(HashSet::new);

    private static final int STACK_TRACE_LENGTH = 10;

    private static final int realStackStartIndex = 2;

    public static final List<String> warnings = new LinkedList<>();

    AccessMonitorRewriter(Config config) {
        super(config);
    }

    public static void clearState() {
        warnings.clear();
        writeLocations.clear();
        alreadyReported.clear();
    }

    private static boolean isInStackTrace(StackTraceElement[] stackTrace) {
         for (StackTraceElement stackTraceElement : stackTrace) {
            if (ifCalledFromPattern.matcher(stackTraceElement.toString()).matches()) {
                return true;
            }
        }
        return false;
    }

    private static void logWarn(String msg) {
        String warn = "[WARN] " + msg;
        if (conf.shouldPrintWarnings) System.out.println(warn);
        warnings.add(warn);
    }

    private static boolean disjoint(Set<Set<Integer>> s1, Set<Set<Integer>> s2) {
        if (s1.isEmpty() || s2.isEmpty()) return true;

        for (Set<?> e1 : s1) {
            for (Set<?> e2 : s2) {
                if (Collections.disjoint(e1, e2)) return true;
            }
        }
        return false;
    }

    public static void reportWriteToMemory(Object accessedObject, String accessedObjectName, String position) {
        synchronized (conf) {
            if (alreadyReported.contains(position)) return;

            Thread thread = Thread.currentThread();
            if (thread.getName().equals("main")) return;

            Object obj = accessedObject == null ? accessedObjectName : accessedObject;
            int identityKey = (System.identityHashCode(obj) + accessedObjectName).hashCode();
            LastAccess last = writeLocations.get(identityKey);

            // Hacky and ugly but fairly effective garbage collector.
            // Using WeakHashMap would be preferable but it doesn't allow to add partially constructed objects.
            // I didn't found a quick way to discover if we are in the constructor call chain.
            if (last != null) {
                Object lastThs = last.ths.get();
                if (lastThs == null) {
                    writeLocations.entrySet().removeIf(entry -> entry.getValue().ths.get() == null);
                    last = null;
                } else if (last.threadId == thread.getId() && last.identityKey() == identityKey) {
                    last.addLocks(locks.get());
                    return;
                }
            }

            LastAccess current = new LastAccess(thread.getId(), accessedObjectName, new WeakReference<>(obj), thread.getName(), locks.get());
            writeLocations.put(identityKey, current);

            if (last != null &&
                    last.threadId != current.threadId &&
                    last.identityKey() == identityKey &&
                    disjoint(current.locks, last.locks)) {
                StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
                current.stackTrace = Arrays.copyOf(stackTrace, stackTrace.length);

                // Optimization. We want stack traces from both workers but we do not want to get it until we know
                // we have violation because it's really slow. So we need 3 hits to reliably get all information.
                if (last.stackTrace == null) return;

                if (!isInStackTrace(current.stackTrace)) return;

                if (alreadyReported.add(position)) {
                    StringBuilder str = new StringBuilder(String.format(
                            "Object %s accessed from multiple threads in %s:\n",
                            accessedObjectName,
                            position));

                    str.append(current.threadName).append(" stack trace:\n");
                    int currentStackTraceIndexEnd = Math.min(realStackStartIndex + STACK_TRACE_LENGTH, current.stackTrace.length);
                    for (int i = realStackStartIndex; i < currentStackTraceIndexEnd; i++) {
                        str.append("    ").append(current.stackTrace[i].toString()).append("\n");
                    }

                    str.append(last.threadName).append(" stack trace:\n");
                    int lastStackTraceIndexEnd = Math.min(realStackStartIndex + STACK_TRACE_LENGTH, last.stackTrace.length);
                    for (int i = realStackStartIndex; i < lastStackTraceIndexEnd; i++) {
                        str.append("    ").append(last.stackTrace[i].toString()).append("\n");
                    }

                    logWarn(str.toString());
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

    private boolean hasWriteInstructions(CodeIterator codeIterator) throws BadBytecode {
        while (codeIterator.hasNext()) {
            int codeIndex = codeIterator.next();
            int opCode = codeIterator.byteAt(codeIndex);
            switch (opCode) {
                case Opcode.PUTFIELD:
                case Opcode.PUTSTATIC:
                case Opcode.MONITORENTER:
                case Opcode.MONITOREXIT:
                case Opcode.AASTORE:
                case Opcode.BASTORE:
                case Opcode.CASTORE:
                case Opcode.DASTORE:
                case Opcode.FASTORE:
                case Opcode.IASTORE:
                case Opcode.LASTORE:
                case Opcode.SASTORE:
                    return true;
            }
        }
        return false;
    }

    @Override
    protected void editMethod(CtMethod editableMethod, String dottedClassName) {
        try {
            CtClass currentClazz = editableMethod.getDeclaringClass();
            ConstPool constPool = currentClazz.getClassFile().getConstPool();

            MethodInfo info = editableMethod.getMethodInfo();
            if (info == null || info.isConstructor()) return;

            CodeAttribute codeAttr = info.getCodeAttribute();
            if (codeAttr == null) return;

            CodeIterator codeIterator = codeAttr.iterator();
            if (codeIterator == null) return;

            if (hasWriteInstructions(codeAttr.iterator())) {
                codeAttr.insertLocalVar(codeAttr.getMaxLocals(), 4);

                while (codeIterator.hasNext()) {
                    int codeIndex = codeIterator.next();
                    int opCode = codeIterator.byteAt(codeIndex);
                    switch (opCode) {
                        case Opcode.PUTFIELD:
                            insertCallToReporterForField(Opcode.DUP, currentClazz,
                                    constPool, info, codeAttr, codeIterator, codeIndex);
                            break;
                        case Opcode.PUTSTATIC:
                            insertCallToReporterForField(Opcode.ACONST_NULL, currentClazz,
                                    constPool, info, codeAttr, codeIterator, codeIndex);
                            break;
                        case Opcode.MONITORENTER:
                            insertCallToSingleArgFunction("addLockToCurrentThread",
                                    constPool, codeIterator, codeIndex);
                            break;
                        case Opcode.MONITOREXIT:
                            insertCallToSingleArgFunction("removeLockFromCurrentThread",
                                    constPool, codeIterator, codeIndex);
                            break;
                        case Opcode.AASTORE:
                        case Opcode.BASTORE:
                        case Opcode.CASTORE:
                        case Opcode.DASTORE:
                        case Opcode.FASTORE:
                        case Opcode.IASTORE:
                        case Opcode.LASTORE:
                        case Opcode.SASTORE:
                            insertCallToReporterForArray(opCode, currentClazz,
                                    constPool, info, codeAttr, codeIterator, codeIndex);
                            break;
                    }
                }

                codeAttr.computeMaxStack();
            }


        } catch (BadBytecode | NotFoundException e) {
            e.printStackTrace();
        }
    }

    private CtClass getArrayElemClass(ClassPool classPool, int opCode) throws NotFoundException {
        switch (opCode) {
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

    private void insertCallToSingleArgFunction(String funcName, ConstPool constPool,
                                               CodeIterator codeIterator, int codeIndex) throws BadBytecode {
        Bytecode code = new Bytecode(constPool);
        code.addOpcode(Opcode.DUP);
        code.addInvokestatic("com.pkukielka.AccessMonitorRewriter", funcName, "(Ljava/lang/Object;)V");
        codeIterator.insert(codeIndex, code.get());
    }

    private void insertCallToReporterForArray(int opCode, CtClass currentClazz, ConstPool constPool,
                                              MethodInfo info, CodeAttribute codeAttr, CodeIterator codeIterator,
                                              int codeIndex) throws BadBytecode, NotFoundException {
        CtClass elemClass = getArrayElemClass(currentClazz.getClassPool(), opCode);
        insertCallToReporter(true, Opcode.DUP, "", elemClass, currentClazz, constPool,
                info, codeAttr, codeIterator, codeIndex);
    }

    private void insertCallToReporterForField(int prepareThisOpcode, CtClass currentClazz, ConstPool constPool,
                                              MethodInfo info, CodeAttribute codeAttr, CodeIterator codeIterator,
                                              int codeIndex) throws BadBytecode, NotFoundException {
        int fieldIndex = codeIterator.byteAt(codeIndex + 2) + (codeIterator.byteAt(codeIndex + 1) << 8);
        CtClass elemClass = getFieldCtClass(currentClazz, constPool, fieldIndex);
        String fqn = getFieldFqn(constPool, fieldIndex);
        insertCallToReporter(false, prepareThisOpcode, fqn, elemClass, currentClazz, constPool,
                info, codeAttr, codeIterator, codeIndex);
    }

    private void insertCallToReporter(boolean isArray, int prepareThisOpcode, String fqn, CtClass elemClass,
                                      CtClass currentClazz, ConstPool constPool, MethodInfo info,
                                      CodeAttribute codeAttr, CodeIterator codeIterator, int codeIndex) throws BadBytecode {
        Bytecode code = new Bytecode(constPool);
        int maxLocals = codeAttr.getMaxLocals();

        code.addStore(maxLocals - 4, elemClass);
        if (isArray) code.addStore(maxLocals - 2, CtClass.intType);

        code.addOpcode(prepareThisOpcode);
        code.addLdc(fqn);
        code.addLdc(currentClazz.getClassFile().getSourceFile() + info.getLineNumber(codeIndex) + "(" + info.getName() + ")");
        code.addInvokestatic(
                "com.pkukielka.AccessMonitorRewriter",
                "reportWriteToMemory",
                "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)V");

        if (isArray) code.addLoad(maxLocals - 2, CtClass.intType);
        code.addLoad(maxLocals - 4, elemClass);

        codeIterator.insert(codeIndex, code.get());
    }
}
