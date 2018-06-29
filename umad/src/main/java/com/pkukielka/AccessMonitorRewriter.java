package com.pkukielka;

import com.typesafe.config.Config;
import javassist.*;
import javassist.bytecode.*;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.regex.Pattern;

class LastAccess {
    WeakReference<Object> ths;
    Set<Integer> locks;
    long threadId;
    String threadName = Thread.currentThread().getName();

    public boolean equals(Object obj) {
        return obj instanceof LastAccess &&
                threadId == ((LastAccess) obj).threadId &&
                locks.equals(((LastAccess) obj).locks) &&
                ths.get() == ((LastAccess) obj).ths.get();
    }

    public int hashCode() {
        return (int) (System.identityHashCode(ths.get()) + 13 * threadId);
    }

    LastAccess(long threadId, Object ths, Collection<Integer> locks) {
        this.ths = new WeakReference<>(ths);
        this.threadId = threadId;
        this.locks = new HashSet<>(locks);
    }
}

public class AccessMonitorRewriter extends MethodRewriter {
    private static final AppConfig conf = AppConfig.get();
    private static final Set<String> alreadyReported = new HashSet<>();
    private static final Pattern ifCalledFromPattern =
            Pattern.compile(conf.getMonitorConfig().getString("ifCalledFrom"));

    private static final String resetMethodFqn =conf.getMonitorConfig().getString("resetMethodFqn");

    private static final HashMap<Long, List<LastAccess>> interestingWriteLocations = new HashMap<>();
    private static final HashMap<Long, LastAccess> writeLocations = new HashMap<>();
    private static final HashMap<Long, HashSet<Integer>> commonLocks = new HashMap<>();

    private static final ThreadLocal<Collection<Integer>> locks = ThreadLocal.withInitial(LinkedList::new);

    private static final int STACK_TRACE_LENGTH = 10;

    private static final int realStackStartIndex = 3;

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

    private static final ThreadLocal<Boolean> isAlreadyProcessing = ThreadLocal.withInitial(() -> false);

    public static void reportWriteToMemory(Object ths,
                                           String accessedObjectName,
                                           String position,
                                           boolean synchronizedMethod) {
        if (isAlreadyProcessing.get()) return;
        try {
            isAlreadyProcessing.set(true);
            synchronized (conf) {
                if (alreadyReported.contains(position)) return;

                Thread thread = Thread.currentThread();
                if (thread.getName().equals("main")) return;

                ths = (ths == null) ? accessedObjectName : ths;

                int thisHashCode = System.identityHashCode(ths);
                Long hashCode = (((long) thisHashCode) << 32) + accessedObjectName.hashCode();

                LastAccess last = writeLocations.get(hashCode);

                if (last != null && last.ths.get() == null) {
                    collectGarbage();
                    last = null;
                }

                Collection<Integer> currentLocks = locks.get();
                if (synchronizedMethod) currentLocks.add(thisHashCode);

                try {
                    if (last != null) {
                        boolean sameThis = ths == last.ths.get();
                        boolean sameLocks = last.locks.containsAll(currentLocks);
                        boolean sameThread = thread.getId() == last.threadId;
                        boolean locksExists = !last.locks.isEmpty();

                        if (sameThis && locksExists && sameLocks) return;
                        if (sameThis && !sameThread && (!locksExists || Collections.disjoint(last.locks, currentLocks)))
                            printStackTrace(accessedObjectName, position, thread);
                        if (!sameThis || (locksExists && !sameLocks)) {
                            // If we have conflict on hash or different set of locks we need to move it to interesting writes set
                            List<LastAccess> lastAccesses = interestingWriteLocations.computeIfAbsent(hashCode, key -> new LinkedList<>());
                            lastAccesses.add(last);
                            writeLocations.remove(hashCode);
                            updateCommonLocks(hashCode, last.locks);

                        }
                    }

                    List<LastAccess> lastAccesses = interestingWriteLocations.get(hashCode);
                    if (lastAccesses != null && Collections.disjoint(currentLocks, commonLocks.get(hashCode))) {
                        for (LastAccess lastAccess : lastAccesses) {
                            if (ths == lastAccess.ths.get() &&
                                    thread.getId() != lastAccess.threadId &&
                                    Collections.disjoint(currentLocks, lastAccess.locks))
                                printStackTrace(accessedObjectName, position, thread);
                        }
                    }

                    LastAccess current = new LastAccess(thread.getId(), ths, currentLocks);
                    if (lastAccesses != null) {
                        interestingWriteLocations.get(hashCode).add(current);
                        updateCommonLocks(hashCode, currentLocks);
                    } else writeLocations.put(hashCode, current);
                } finally {
                    if (synchronizedMethod) currentLocks.remove((Integer)thisHashCode);
                }
            }
        } finally {
            isAlreadyProcessing.set(false);
        }
    }

    private static void collectGarbage() {
        interestingWriteLocations.forEach((key, value) -> value.removeIf(e -> e.ths.get() == null));
        interestingWriteLocations.entrySet().removeIf(e -> e.getValue().isEmpty());
        writeLocations.entrySet().removeIf(e -> e.getValue().ths.get() == null);
    }

    // Key trick to optimize speed. We assume that if the same variable is accessed with dozens
    // of different locks it probably means there is one or two important ones and rest is accidental
    // due to some other synchronizations long the way.
    // If that is the case we should check that locks first.
    private static void updateCommonLocks(Long hashCode, Collection<Integer> currentLocks) {
        HashSet<Integer> locks = new HashSet<>(currentLocks);
        if (commonLocks.containsKey(hashCode)) locks.retainAll(commonLocks.get(hashCode));
        commonLocks.put(hashCode, locks);
    }

    private static void printStackTrace(String accessedObjectName, String position, Thread thread) {
        StackTraceElement[] stackTrace = thread.getStackTrace();
        if (!isInStackTrace(stackTrace)) return;

        if (alreadyReported.add(position)) {
            StringBuilder str = new StringBuilder(String.format(
                    "Object %s accessed from multiple threads in %s:\n",
                    accessedObjectName,
                    position));

            str.append(thread.getName()).append(" stack trace:\n");
            int currentStackTraceIndexEnd = Math.min(realStackStartIndex + STACK_TRACE_LENGTH, stackTrace.length);
            for (int i = realStackStartIndex; i < currentStackTraceIndexEnd; i++) {
                str.append("    ").append(stackTrace[i].toString()).append("\n");
            }

            logWarn(str.toString());
        }
    }

    public static void addLockToCurrentThread(Object o) {
        locks.get().add(System.identityHashCode(o));
    }

    public static void removeLockFromCurrentThread(Object o) {
        locks.get().remove((Integer)System.identityHashCode(o));
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
            if (editableMethod.getLongName().equals(resetMethodFqn))
                editableMethod.insertAfter("com.pkukielka.AccessMonitorRewriter.clearState();");

        } catch (BadBytecode | NotFoundException | CannotCompileException e) {
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

    private static final int IgnoredFlags = Modifier.TRANSIENT | Modifier.VOLATILE;

    private void insertCallToReporterForField(int prepareThisOpcode, CtClass currentClazz, ConstPool constPool,
                                              MethodInfo info, CodeAttribute codeAttr, CodeIterator codeIterator,
                                              int codeIndex) throws BadBytecode, NotFoundException {
        int fieldIndex = codeIterator.byteAt(codeIndex + 2) + (codeIterator.byteAt(codeIndex + 1) << 8);

        String fieldName = constPool.getFieldrefName(fieldIndex);

        CtClass elemClass = getFieldCtClass(currentClazz, constPool, fieldIndex);
        String className = constPool.getFieldrefClassName(fieldIndex);

        CtClass fieldClass = currentClazz.getClassPool().get(className);
        CtField field = fieldClass.getField(fieldName);
        if ((field.getModifiers() & IgnoredFlags) != 0) return;

        String fqn = className + "." + fieldName;

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
        code.addLdc(currentClazz.getClassFile().getSourceFile() + ":" + info.getLineNumber(codeIndex));
        code.addIconst(info.getAccessFlags() & AccessFlag.SYNCHRONIZED);
        code.addInvokestatic(
                "com.pkukielka.AccessMonitorRewriter",
                "reportWriteToMemory",
                "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Z)V");

        if (isArray) code.addLoad(maxLocals - 2, CtClass.intType);
        code.addLoad(maxLocals - 4, elemClass);

        codeIterator.insert(codeIndex, code.get());
    }
}
