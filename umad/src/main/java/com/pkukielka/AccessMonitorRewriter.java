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

class AccessKey {
    private String fieldName;
    private WeakReference<Object> obj;
    private int hash;

    public volatile static boolean needsCleanup = false;

    public AccessKey(String fieldName, Object obj){
        this.fieldName = fieldName;
        this.obj = new WeakReference<>(obj);
        this.hash = System.identityHashCode(obj);
    }

    @Override
    public int hashCode() {
        return hash * 37 + fieldName.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj instanceof AccessKey){
            AccessKey other = (AccessKey) obj;
            Object pointingObj = this.obj.get();
            if(pointingObj == null){
                needsCleanup = true;
                return false;
            }
            return other.obj.get() == pointingObj && other.fieldName.equals(this.fieldName);
        }
        return false;
    }

    public Object getObj() {
        return obj.get();
    }
}

class LastAccess {
    long threadId;
    StackTraceElement[] stackTrace;
    String threadName;
    int[] locks = new int[8];
    int firstFree = 0;
    private final static int MARKER =  System.identityHashCode(null);

    LastAccess(long threadId, String threadName, Set<Integer> locks) {
        this.threadId = threadId;
        this.stackTrace = null;
        this.threadName = threadName;
        addLocks(locks);
    }

    void addLocks(Set<Integer> currentLocks) {
        if (currentLocks.isEmpty()) return;

        int newCount = currentLocks.size();
        int[] currentLocksA = new int[newCount];

        int j = 0;
        for(Integer lock: currentLocks){
            currentLocksA[j] = lock;
            j++;
        }

        int similarCount = 0;
        for (j = 0; j < firstFree; j++){
            if (similarCount == currentLocksA.length) return;
            int current = locks[j];
            if (current == MARKER) similarCount = 0;
            else {
                if (currentLocksA[similarCount] == locks[j]) similarCount++;
                else similarCount = 0;
            }
        }

        if (firstFree + newCount + 1 > locks.length){
            int[] newLocks = new int[Math.max(locks.length * 2, locks.length + newCount + 1)];
            System.arraycopy(locks, 0, newLocks, 0, firstFree);
            locks = newLocks;
        }
        int i = firstFree;
        firstFree = firstFree + newCount + 1;
        for(Integer lock: currentLocks){
            locks[i] = lock;
            i++;
        }
        locks[i] = MARKER;
    }

    public boolean safeAccessed(Set<Integer> currentLocks){
        if (currentLocks.isEmpty()) return false;

        boolean seenLock = false;
        for(int i = 0; i < firstFree; i++){
            int current = locks[i];
            if (current == MARKER) {
                if (!seenLock)
                    return false;
                seenLock = false;
            } else if (!seenLock) seenLock = currentLocks.contains(current);
        }
        return firstFree != 0;
    }
}

public class AccessMonitorRewriter extends MethodRewriter {
    private static final AppConfig conf = AppConfig.get();
    private static final Map<AccessKey, LastAccess> writeLocations = new HashMap<>();
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

    private static void potentialValioation(LastAccess last, String position, String fieldName){
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();

        // Optimization. We want stack traces from both workers but we do not want to get it until we know
        // we have violation because it's really slow. So we need 3 hits to reliably get all information.
        if (last.stackTrace == null) {
            // TODO we need to store such cases somewhere
            last.stackTrace = stackTrace;
            return;
        }

        if (!isInStackTrace(stackTrace)) return;

        if (alreadyReported.add(position)) {
            StringBuilder str = new StringBuilder(String.format(
                    "Object %s accessed from multiple threads in %s:\n",
                    fieldName,
                    position));

            String threadName = Thread.currentThread().getName();

            str.append(threadName).append(" stack trace:\n");
            int currentStackTraceIndexEnd = Math.min(realStackStartIndex + STACK_TRACE_LENGTH, stackTrace.length);
            for (int i = realStackStartIndex; i < currentStackTraceIndexEnd; i++) {
                str.append("    ").append(stackTrace[i].toString()).append("\n");
            }

            str.append(last.threadName).append(" stack trace:\n");
            int lastStackTraceIndexEnd = Math.min(realStackStartIndex + STACK_TRACE_LENGTH, last.stackTrace.length);
            for (int i = realStackStartIndex; i < lastStackTraceIndexEnd; i++) {
                str.append("    ").append(last.stackTrace[i].toString()).append("\n");
            }

            logWarn(str.toString());
        }
    }

    public static synchronized void reportWriteToMemory(Object accessedObject, String accessedObjectField, String position) {
        if (alreadyReported.contains(position)) return;

        Thread thread = Thread.currentThread();
        if (thread.getName().equals("main")) return;

        Object obj = accessedObject == null ? accessedObjectField : accessedObject;
        AccessKey key = new AccessKey(accessedObjectField, obj);
        LastAccess last = writeLocations.get(key);
        long threadId = thread.getId();

        // Hacky and ugly but fairly effective garbage collector.
        // Using WeakHashMap would be preferable but it doesn't allow to add partially constructed objects.
        // I didn't found a quick way to discover if we are in the constructor call chain.
        if (AccessKey.needsCleanup) {
            AccessKey.needsCleanup = false;
            writeLocations.entrySet().removeIf(entry -> entry.getKey().getObj() == null);
        }

        if (last != null) {
            Set<Integer> currentLocks = locks.get();

            if (last.threadId != threadId && !last.safeAccessed(currentLocks))
                potentialValioation(last, position, accessedObjectField);

            last.addLocks(currentLocks);
        } else {
            LastAccess current = new LastAccess(thread.getId(), thread.getName(), locks.get());
            writeLocations.put(key, current);
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
            System.out.println("[ERROR] Unable to modify method: " + editableMethod.getLongName() + " from " + dottedClassName);
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
