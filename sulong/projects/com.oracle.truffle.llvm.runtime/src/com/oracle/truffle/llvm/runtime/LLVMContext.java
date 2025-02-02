/*
 * Copyright (c) 2016, 2020, Oracle and/or its affiliates.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.oracle.truffle.llvm.runtime;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleFile;
import com.oracle.truffle.api.TruffleLanguage.ContextReference;
import com.oracle.truffle.api.TruffleLanguage.Env;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlot;
import com.oracle.truffle.api.frame.FrameUtil;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ControlFlowException;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.utilities.AssumedValue;
import com.oracle.truffle.llvm.api.Toolchain;
import com.oracle.truffle.llvm.runtime.LLVMArgumentBuffer.LLVMArgumentArray;
import com.oracle.truffle.llvm.runtime.LLVMFunctionCode.Function;
import com.oracle.truffle.llvm.runtime.LLVMFunctionCode.UnresolvedFunction;
import com.oracle.truffle.llvm.runtime.LLVMLanguage.Loader;
import com.oracle.truffle.llvm.runtime.datalayout.DataLayout;
import com.oracle.truffle.llvm.runtime.debug.LLVMSourceContext;
import com.oracle.truffle.llvm.runtime.debug.type.LLVMSourceType;
import com.oracle.truffle.llvm.runtime.except.LLVMLinkerException;
import com.oracle.truffle.llvm.runtime.global.LLVMGlobal;
import com.oracle.truffle.llvm.runtime.global.LLVMGlobalContainer;
import com.oracle.truffle.llvm.runtime.instruments.trace.LLVMTracerInstrument;
import com.oracle.truffle.llvm.runtime.interop.LLVMTypedForeignObject;
import com.oracle.truffle.llvm.runtime.interop.access.LLVMInteropType;
import com.oracle.truffle.llvm.runtime.memory.LLVMMemory;
import com.oracle.truffle.llvm.runtime.memory.LLVMMemory.HandleContainer;
import com.oracle.truffle.llvm.runtime.memory.LLVMMemoryOpNode;
import com.oracle.truffle.llvm.runtime.memory.LLVMStack;
import com.oracle.truffle.llvm.runtime.memory.LLVMStack.StackPointer;
import com.oracle.truffle.llvm.runtime.memory.LLVMThreadingStack;
import com.oracle.truffle.llvm.runtime.nodes.api.LLVMStatementNode;
import com.oracle.truffle.llvm.runtime.nodes.intrinsics.sulong.LLVMPrintToolchainPath;
import com.oracle.truffle.llvm.runtime.options.SulongEngineOption;
import com.oracle.truffle.llvm.runtime.options.TargetStream;
import com.oracle.truffle.llvm.runtime.pointer.LLVMManagedPointer;
import com.oracle.truffle.llvm.runtime.pointer.LLVMNativePointer;
import com.oracle.truffle.llvm.runtime.pointer.LLVMPointer;
import com.oracle.truffle.llvm.runtime.pthread.LLVMPThreadContext;
import com.oracle.truffle.llvm.runtime.types.FunctionType;
import com.oracle.truffle.llvm.runtime.types.Type;
import com.oracle.truffle.llvm.runtime.types.VoidType;

public final class LLVMContext {

    private final List<Path> libraryPaths = new ArrayList<>();
    private final Object libraryPathsLock = new Object();
    private final Toolchain toolchain;
    @CompilationFinal private Path internalLibraryPath;
    private final List<ExternalLibrary> externalLibraries = new ArrayList<>();
    private final Object externalLibrariesLock = new Object();
    private final List<String> internalLibraryNames;

    // map that contains all non-native globals, needed for pointer->global lookups
    private final ConcurrentHashMap<LLVMPointer, LLVMGlobal> globalsReverseMap = new ConcurrentHashMap<>();
    // allocations used to store non-pointer globals (need to be freed when context is disposed)
    private final ArrayList<LLVMPointer> globalsNonPointerStore = new ArrayList<>();
    private final ArrayList<LLVMPointer> globalsReadOnlyStore = new ArrayList<>();
    private final Object globalsStoreLock = new Object();

    private final List<LLVMThread> runningThreads = new ArrayList<>();
    @CompilationFinal private LLVMThreadingStack threadingStack;
    private Object[] mainArguments;     // effectively final after initialization
    private final ArrayList<LLVMNativePointer> caughtExceptionStack = new ArrayList<>();
    private ConcurrentHashMap<String, Integer> nativeCallStatistics;        // effectively final
    // after initialization

    private final HandleContainer handleContainer;
    private final HandleContainer derefHandleContainer;

    private final LLVMSourceContext sourceContext;

    @CompilationFinal private Env env;
    private final LLVMScope globalScope;
    private final DynamicLinkChain dynamicLinkChain;
    private final List<RootCallTarget> destructorFunctions;
    private final LLVMFunctionPointerRegistry functionPointerRegistry;
    private final LLVMInteropType.InteropTypeRegistry interopTypeRegistry;

    // we are not able to clean up ThreadLocals properly, so we are using maps instead
    private final Map<Thread, Object> tls = new ConcurrentHashMap<>();

    // private for storing the globals of each bcode file;
    @CompilationFinal(dimensions = 2) private AssumedValue<LLVMPointer>[][] symbolStorage;

    // signals
    private final LLVMNativePointer sigDfl;
    private final LLVMNativePointer sigIgn;
    private final LLVMNativePointer sigErr;

    // pThread state
    private final LLVMPThreadContext pThreadContext;

    private boolean initialized;
    private boolean cleanupNecessary;
    private boolean initializeContextCalled;
    private DataLayout libsulongDatalayout;
    private Boolean datalayoutInitialised;
    private final LLVMLanguage language;

    private LLVMTracerInstrument tracer;    // effectively final after initialization

    private final class LLVMFunctionPointerRegistry {
        private final HashMap<LLVMNativePointer, LLVMFunctionDescriptor> functionDescriptors = new HashMap<>();

        synchronized LLVMFunctionDescriptor getDescriptor(LLVMNativePointer pointer) {
            return functionDescriptors.get(pointer);
        }

        synchronized void register(LLVMNativePointer pointer, LLVMFunctionDescriptor desc) {
            functionDescriptors.put(pointer, desc);
        }

        synchronized LLVMFunctionDescriptor create(LLVMFunction functionDetail) {
            return new LLVMFunctionDescriptor(LLVMContext.this, functionDetail);
        }
    }

    @SuppressWarnings("unchecked")
    LLVMContext(LLVMLanguage language, Env env, Toolchain toolchain) {
        this.language = language;
        this.libsulongDatalayout = null;
        this.datalayoutInitialised = false;
        this.env = env;
        this.initialized = false;
        this.cleanupNecessary = false;
        this.destructorFunctions = new ArrayList<>();
        this.nativeCallStatistics = SulongEngineOption.optionEnabled(env.getOptions().get(SulongEngineOption.NATIVE_CALL_STATS)) ? new ConcurrentHashMap<>() : null;
        this.sigDfl = LLVMNativePointer.create(0);
        this.sigIgn = LLVMNativePointer.create(1);
        this.sigErr = LLVMNativePointer.create(-1);
        LLVMMemory memory = language.getLLVMMemory();
        this.handleContainer = memory.createHandleContainer(false, language.getNoCommonHandleAssumption());
        this.derefHandleContainer = memory.createHandleContainer(true, language.getNoDerefHandleAssumption());
        this.functionPointerRegistry = new LLVMFunctionPointerRegistry();
        this.interopTypeRegistry = new LLVMInteropType.InteropTypeRegistry();
        this.sourceContext = new LLVMSourceContext();
        this.toolchain = toolchain;

        this.internalLibraryNames = Collections.unmodifiableList(Arrays.asList(language.getCapability(PlatformCapability.class).getSulongDefaultLibraries()));
        assert !internalLibraryNames.isEmpty() : "No internal libraries?";

        this.globalScope = new LLVMScope();
        this.dynamicLinkChain = new DynamicLinkChain();

        this.mainArguments = getMainArguments(env);

        addLibraryPaths(SulongEngineOption.getPolyglotOptionSearchPaths(env));

        pThreadContext = new LLVMPThreadContext(this);

        symbolStorage = new AssumedValue[10][];
    }

    boolean patchContext(Env newEnv) {
        if (this.initializeContextCalled) {
            return false;
        }
        this.env = newEnv;
        this.nativeCallStatistics = SulongEngineOption.optionEnabled(this.env.getOptions().get(SulongEngineOption.NATIVE_CALL_STATS)) ? new ConcurrentHashMap<>() : null;
        this.mainArguments = getMainArguments(newEnv);
        return true;
    }

    private static Object[] getMainArguments(Env environment) {
        Object mainArgs = environment.getConfig().get(LLVMLanguage.MAIN_ARGS_KEY);
        return mainArgs == null ? environment.getApplicationArguments() : (Object[]) mainArgs;
    }

    private static final class InitializeContextNode extends LLVMStatementNode {

        @CompilationFinal private ContextReference<LLVMContext> ctxRef;
        private final FrameSlot stackPointer;

        @Child DirectCallNode initContext;

        InitializeContextNode(LLVMContext ctx, FrameDescriptor rootFrame) {
            this.stackPointer = rootFrame.findFrameSlot(LLVMStack.FRAME_ID);

            LLVMFunction function = ctx.globalScope.getFunction("__sulong_init_context");
            LLVMFunctionDescriptor initContextDescriptor = ctx.createFunctionDescriptor(function);
            RootCallTarget initContextFunction = initContextDescriptor.getFunctionCode().getLLVMIRFunctionSlowPath();
            this.initContext = DirectCallNode.create(initContextFunction);
        }

        @Override
        public void execute(VirtualFrame frame) {
            if (ctxRef == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                ctxRef = lookupContextReference(LLVMLanguage.class);
            }
            LLVMContext ctx = ctxRef.get();
            if (!ctx.initialized) {
                assert !ctx.cleanupNecessary;
                ctx.initialized = true;
                ctx.cleanupNecessary = true;
                try (StackPointer sp = ((StackPointer) FrameUtil.getObjectSafe(frame, stackPointer)).newFrame()) {
                    Object[] args = new Object[]{sp, ctx.getApplicationArguments(), getEnvironmentVariables(), getRandomValues()};
                    initContext.call(args);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    void initialize() {
        this.initializeContextCalled = true;
        assert this.threadingStack == null;

        final String traceOption = env.getOptions().get(SulongEngineOption.TRACE_IR);
        if (SulongEngineOption.optionEnabled(traceOption)) {
            if (!env.getOptions().get(SulongEngineOption.LL_DEBUG)) {
                throw new IllegalStateException("\'--llvm.traceIR\' requires \'--llvm.llDebug=true\'");
            }
            tracer = new LLVMTracerInstrument(env, traceOption);
        }

        this.threadingStack = new LLVMThreadingStack(Thread.currentThread(), parseStackSize(env.getOptions().get(SulongEngineOption.STACK_SIZE)));
        for (ContextExtension ext : language.getLanguageContextExtension()) {
            ext.initialize();
        }
        String languageHome = language.getLLVMLanguageHome();
        if (languageHome != null) {
            PlatformCapability<?> sysContextExt = language.getCapability(PlatformCapability.class);
            internalLibraryPath = Paths.get(languageHome).resolve(sysContextExt.getSulongLibrariesPath());
            // add internal library location also to the external library lookup path
            addLibraryPath(internalLibraryPath.toString());
        }
        Loader loader = getLanguage().getCapability(Loader.class);
        loader.loadDefaults(this, internalLibraryPath);
        if (env.getOptions().get(SulongEngineOption.PRINT_TOOLCHAIN_PATH)) {
            Function function = new UnresolvedFunction();
            // This function currently has the index 0, as it is the first function defined in the
            // table reserved for misc functions
            LLVMFunction functionDetail = LLVMFunction.create(LLVMPrintToolchainPath.NAME, null, function, new FunctionType(VoidType.INSTANCE, new Type[0], false), LLVMSymbol.MISCFUNCTION_ID,
                            LLVMSymbol.getMiscSymbolIndex());
            LLVMFunctionDescriptor functionDescriptor = createFunctionDescriptor(functionDetail);
            functionDescriptor.getFunctionCode().define(getLanguage().getCapability(LLVMIntrinsicProvider.class), null);
            registerSymbolTable(0, new AssumedValue[5]);
            AssumedValue<LLVMPointer>[] symbols = symbolStorage[functionDetail.getBitcodeID(false)];
            symbols[functionDetail.getSymbolIndex(false)] = new AssumedValue<>(LLVMManagedPointer.create(functionDescriptor));
            globalScope.register(functionDetail);
        }
    }

    private static long parseStackSize(String v) {
        String valueString = v.trim();
        long scale = 1;
        switch (valueString.charAt(valueString.length() - 1)) {
            case 'k':
            case 'K':
                scale = 1024L;
                break;
            case 'm':
            case 'M':
                scale = 1024L * 1024L;
                break;
            case 'g':
            case 'G':
                scale = 1024L * 1024L * 1024L;
                break;
            case 't':
            case 'T':
                scale = 1024L * 1024L * 1024L * 1024L;
                break;
        }

        if (scale != 1) {
            /* Remove trailing scale character. */
            valueString = valueString.substring(0, valueString.length() - 1);
        }

        return Long.parseLong(valueString) * scale;
    }

    public boolean isInitialized() {
        return threadingStack != null;
    }

    public LLVMStatementNode createInitializeContextNode(FrameDescriptor rootFrame) {
        // we can't do the initialization in the LLVMContext constructor nor in
        // Sulong.createContext() because Truffle is not properly initialized there. So, we need to
        // do it in a delayed way.
        return new InitializeContextNode(this, rootFrame);
    }

    public Toolchain getToolchain() {
        return toolchain;
    }

    @TruffleBoundary
    private LLVMManagedPointer getApplicationArguments() {
        String[] result;
        if (mainArguments == null) {
            result = new String[]{""};
        } else {
            result = new String[mainArguments.length + 1];
            // we don't have an application path at this point in time. it will be overwritten when
            // _start is called
            result[0] = "";
            for (int i = 1; i < result.length; i++) {
                result[i] = mainArguments[i - 1].toString();
            }
        }
        return toTruffleObjects(result);
    }

    @TruffleBoundary
    private static LLVMManagedPointer getEnvironmentVariables() {
        String[] result = System.getenv().entrySet().stream().map((e) -> e.getKey() + "=" + e.getValue()).toArray(String[]::new);
        return toTruffleObjects(result);
    }

    @TruffleBoundary
    private static LLVMManagedPointer getRandomValues() {
        byte[] result = new byte[16];
        secureRandom().nextBytes(result);
        return toManagedPointer(new LLVMArgumentBuffer(result));
    }

    private static SecureRandom secureRandom() {
        return new SecureRandom();
    }

    private static LLVMManagedPointer toTruffleObjects(String[] values) {
        LLVMArgumentBuffer[] result = new LLVMArgumentBuffer[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = new LLVMArgumentBuffer(values[i]);
        }
        return toManagedPointer(new LLVMArgumentArray(result));
    }

    private static LLVMManagedPointer toManagedPointer(Object value) {
        return LLVMManagedPointer.create(LLVMTypedForeignObject.createUnknown(value));
    }

    public void addLibsulongDataLayout(DataLayout datalayout) {
        // Libsulong datalayout can only be set once. This should be called by
        // Runner#parseDefaultLibraries.
        if (!datalayoutInitialised) {
            this.libsulongDatalayout = datalayout;
            datalayoutInitialised = true;
        } else {
            throw new NullPointerException("The default datalayout cannot be overrwitten.");
        }
    }

    public DataLayout getLibsulongDataLayout() {
        return libsulongDatalayout;
    }

    void finalizeContext() {
        // join all created pthread - threads
        pThreadContext.joinAllThreads();

        // the following cases exist for cleanup:
        // - exit() or interop: execute all atexit functions, shutdown stdlib, flush IO, and execute
        // destructors
        // - _exit(), _Exit(), or abort(): no cleanup necessary
        if (cleanupNecessary) {
            try {
                LLVMFunction function = globalScope.getFunction("__sulong_dispose_context");
                AssumedValue<LLVMPointer>[] functions = findSymbolTable(function.getBitcodeID(false));
                int index = function.getSymbolIndex(false);
                LLVMPointer pointer = functions[index].get();
                if (LLVMManagedPointer.isInstance(pointer)) {
                    LLVMFunctionDescriptor functionDescriptor = (LLVMFunctionDescriptor) LLVMManagedPointer.cast(pointer).getObject();
                    RootCallTarget disposeContext = functionDescriptor.getFunctionCode().getLLVMIRFunctionSlowPath();
                    try (StackPointer stackPointer = threadingStack.getStack().newFrame()) {
                        disposeContext.call(stackPointer);
                    }
                } else {
                    throw new IllegalStateException("Context cannot be disposed: Function _sulong_dispose_context is not a function or enclosed inside a LLVMManagedPointer.");
                }
            } catch (ControlFlowException e) {
                // nothing needs to be done as the behavior is not defined
            }
        }
    }

    private CallTarget freeGlobalBlocks;

    private void initFreeGlobalBlocks(NodeFactory nodeFactory) {
        // lazily initialized, this is not necessary if there are no global blocks allocated
        if (freeGlobalBlocks == null) {
            freeGlobalBlocks = Truffle.getRuntime().createCallTarget(new RootNode(language) {

                @Child LLVMMemoryOpNode freeRo = nodeFactory.createFreeGlobalsBlock(true);
                @Child LLVMMemoryOpNode freeRw = nodeFactory.createFreeGlobalsBlock(false);

                @Override
                public Object execute(VirtualFrame frame) {
                    // Executed in dispose(), therefore can read unsynchronized
                    for (LLVMPointer store : globalsReadOnlyStore) {
                        if (store != null) {
                            freeRo.execute(store);
                        }
                    }
                    for (LLVMPointer store : globalsNonPointerStore) {
                        if (store != null) {
                            freeRw.execute(store);
                        }
                    }
                    return null;
                }
            });
        }
    }

    void dispose(LLVMMemory memory) {
        printNativeCallStatistics();

        if (isInitialized()) {
            threadingStack.freeMainStack(memory);
        }

        if (freeGlobalBlocks != null) {
            // free the space allocated for non-pointer globals
            freeGlobalBlocks.call();
        }

        // free the space which might have been when putting pointer-type globals into native memory
        for (LLVMPointer pointer : globalsReverseMap.keySet()) {
            if (LLVMManagedPointer.isInstance(pointer)) {
                Object object = LLVMManagedPointer.cast(pointer).getObject();
                if (object instanceof LLVMGlobalContainer) {
                    ((LLVMGlobalContainer) object).dispose();
                }
            }
        }

        if (tracer != null) {
            tracer.dispose();
        }

        if (loaderTraceStream != null) {
            loaderTraceStream.dispose();
        }

        if (syscallTraceStream != null) {
            syscallTraceStream.dispose();
        }

        if (nativeCallStatsStream != null) {
            assert nativeCallStatistics != null;
            nativeCallStatsStream.dispose();
        }

        if (lifetimeAnalysisStream != null) {
            lifetimeAnalysisStream.dispose();
        }
    }

    public ExternalLibrary addInternalLibrary(String lib, boolean isNative) {
        CompilerAsserts.neverPartOfCompilation();
        Path path = locateInternalLibrary(lib);
        return getOrAddExternalLibrary(ExternalLibrary.internalFromPath(path, isNative));
    }

    @TruffleBoundary
    private Path locateInternalLibrary(String lib) {
        if (internalLibraryPath == null) {
            throw new LLVMLinkerException(String.format("Cannot load \"%s\". Internal library path not set.", lib));
        }
        Path absPath = internalLibraryPath.resolve(lib);
        if (absPath.toFile().exists()) {
            return absPath;
        }
        return Paths.get(lib);
    }

    /**
     * @see #addExternalLibrary(String, Object, LibraryLocator)
     */
    public ExternalLibrary addExternalLibraryDefaultLocator(String lib, Object reason) {
        return addExternalLibrary(lib, reason, DefaultLibraryLocator.INSTANCE);
    }

    /**
     * Adds a new library to the context (if not already added). It is assumed that the library is a
     * native one until it is parsed and we know for sure.
     * 
     * @see ExternalLibrary#makeBitcodeLibrary
     * @return null if already added
     */
    public ExternalLibrary addExternalLibrary(String lib, Object reason, LibraryLocator locator) {
        CompilerAsserts.neverPartOfCompilation();
        if (isInternalLibrary(lib)) {
            // Disallow loading internal libraries explicitly.
            return null;
        }
        ExternalLibrary newLib = createExternalLibrary(lib, reason, locator);
        ExternalLibrary existingLib = getOrAddExternalLibrary(newLib);
        if (existingLib == newLib) {
            return newLib;
        }
        LibraryLocator.traceAlreadyLoaded(this, existingLib);
        return null;
    }

    /**
     * Finds an already added library. Note that this might return
     * {@link ExternalLibrary#isInternal() internal libraries}.
     * 
     * @return null if not yet loaded
     */
    public ExternalLibrary findExternalLibrary(String lib, Object reason, LibraryLocator locator) {
        final ExternalLibrary newLib;
        if (isInternalLibrary(lib)) {
            Path path = locateInternalLibrary(lib);
            newLib = ExternalLibrary.internalFromPath(path, true);
        } else {
            newLib = createExternalLibrary(lib, reason, locator);
        }
        return getExternalLibrary(newLib);
    }

    /**
     * Creates a new external library. It is assumed that the library is a native one until it is
     * parsed and we know for sure.
     * 
     * @see ExternalLibrary#makeBitcodeLibrary
     */
    private ExternalLibrary createExternalLibrary(String lib, Object reason, LibraryLocator locator) {
        boolean isNative = true;
        if (isInternalLibrary(lib)) {
            // Disallow loading internal libraries explicitly.
            throw new AssertionError("loading internal libraries explicitly is not allowed");
        }
        TruffleFile tf = locator.locate(this, lib, reason);
        ExternalLibrary newLib;
        if (tf == null) {
            // Unable to locate the library -> will go to native
            Path path = Paths.get(lib);
            LibraryLocator.traceDelegateNative(this, path);
            newLib = ExternalLibrary.externalFromPath(path, isNative);
        } else {
            newLib = ExternalLibrary.externalFromFile(tf, isNative);
        }
        return newLib;
    }

    /**
     * @return null if already loaded
     */
    public ExternalLibrary addExternalLibrary(ExternalLibrary newLib) {
        CompilerAsserts.neverPartOfCompilation();
        if (isInternalLibrary(newLib.getName())) {
            // Disallow loading internal libraries explicitly.
            return null;
        }
        ExternalLibrary existingLib = getOrAddExternalLibrary(newLib);
        if (existingLib == newLib) {
            return newLib;
        }
        LibraryLocator.traceAlreadyLoaded(this, existingLib);
        return null;
    }

    private boolean isInternalLibrary(String lib) {
        for (String interalLibs : internalLibraryNames) {
            if (interalLibs.equals(lib)) {
                return true;
            }
        }
        return false;
    }

    private ExternalLibrary getExternalLibrary(ExternalLibrary externalLib) {
        synchronized (externalLibrariesLock) {
            int index = externalLibraries.indexOf(externalLib);
            if (index >= 0) {
                ExternalLibrary ret = externalLibraries.get(index);
                assert ret.equals(externalLib);
                return ret;
            }
            return null;
        }
    }

    private ExternalLibrary getOrAddExternalLibrary(ExternalLibrary externalLib) {
        synchronized (externalLibrariesLock) {
            int index = externalLibraries.indexOf(externalLib);
            if (index >= 0) {
                ExternalLibrary ret = externalLibraries.get(index);
                assert ret.equals(externalLib);
                return ret;
            } else {
                externalLibraries.add(externalLib);
                return externalLib;
            }
        }
    }

    public List<ExternalLibrary> getExternalLibraries(Predicate<ExternalLibrary> filter) {
        synchronized (externalLibrariesLock) {
            return externalLibraries.stream().filter(f -> filter.test(f)).collect(Collectors.toList());
        }
    }

    public void addLibraryPaths(List<String> paths) {
        for (String p : paths) {
            addLibraryPath(p);
        }
    }

    private void addLibraryPath(String p) {
        Path path = Paths.get(p);
        TruffleFile file = getEnv().getInternalTruffleFile(path.toString());
        if (file.isDirectory()) {
            synchronized (libraryPathsLock) {
                if (!libraryPaths.contains(path)) {
                    libraryPaths.add(path);
                }
            }
        }

        // TODO (chaeubl): we should throw an exception in this case but this will cause gate
        // failures at the moment, because the library path is not always set correctly
    }

    List<Path> getLibraryPaths() {
        synchronized (libraryPathsLock) {
            return libraryPaths;
        }
    }

    public LLVMLanguage getLanguage() {
        return language;
    }

    public Env getEnv() {
        return env;
    }

    public LLVMScope getGlobalScope() {
        return globalScope;
    }

    public AssumedValue<LLVMPointer>[] findSymbolTable(int id) {
        return symbolStorage[id];
    }

    public boolean symbolTableExists(int id) {
        return id < symbolStorage.length && symbolStorage[id] != null;
    }

    @SuppressWarnings("unchecked")
    @TruffleBoundary
    public void registerSymbolTable(int index, AssumedValue<LLVMPointer>[] target) {
        synchronized (this) {
            if (index < symbolStorage.length && symbolStorage[index] == null) {
                symbolStorage[index] = target;
            } else if (index >= symbolStorage.length) {
                int newLength = (index + 1) + ((index + 1) / 2);
                AssumedValue<LLVMPointer>[][] temp = new AssumedValue[newLength][];
                System.arraycopy(symbolStorage, 0, temp, 0, symbolStorage.length);
                symbolStorage = temp;
                symbolStorage[index] = target;
            } else {
                throw new IllegalStateException("Registering a new symbol table for an existing id. ");
            }
        }
    }

    @TruffleBoundary
    public Object getThreadLocalStorage() {
        Object value = tls.get(Thread.currentThread());
        if (value != null) {
            return value;
        }
        return LLVMNativePointer.createNull();
    }

    @TruffleBoundary
    public void setThreadLocalStorage(Object value) {
        tls.put(Thread.currentThread(), value);
    }

    @TruffleBoundary
    public LLVMFunctionDescriptor getFunctionDescriptor(LLVMNativePointer handle) {
        return functionPointerRegistry.getDescriptor(handle);
    }

    @TruffleBoundary
    public LLVMFunctionDescriptor createFunctionDescriptor(LLVMFunction functionDetail) {
        return functionPointerRegistry.create(functionDetail);
    }

    @TruffleBoundary
    public void registerFunctionPointer(LLVMNativePointer address, LLVMFunctionDescriptor descriptor) {
        functionPointerRegistry.register(address, descriptor);
    }

    public LLVMNativePointer getSigDfl() {
        return sigDfl;
    }

    public LLVMNativePointer getSigIgn() {
        return sigIgn;
    }

    public LLVMNativePointer getSigErr() {
        return sigErr;
    }

    public HandleContainer getHandleContainer() {
        return handleContainer;
    }

    public HandleContainer getDerefHandleContainer() {
        return derefHandleContainer;
    }

    @TruffleBoundary
    public void registerNativeCall(LLVMFunctionDescriptor descriptor) {
        if (nativeCallStatistics != null) {
            String name = descriptor.getLLVMFunction().getName() + " " + descriptor.getLLVMFunction().getType();
            if (nativeCallStatistics.containsKey(name)) {
                int count = nativeCallStatistics.get(name) + 1;
                nativeCallStatistics.put(name, count);
            } else {
                nativeCallStatistics.put(name, 1);
            }
        }
    }

    public List<LLVMNativePointer> getCaughtExceptionStack() {
        return caughtExceptionStack;
    }

    public LLVMThreadingStack getThreadingStack() {
        assert threadingStack != null;
        return threadingStack;
    }

    public void registerDestructorFunctions(RootCallTarget destructor) {
        assert destructor != null;
        assert !destructorFunctions.contains(destructor);
        destructorFunctions.add(destructor);
    }

    @TruffleBoundary
    public boolean isScopeLoaded(LLVMScope scope) {
        return dynamicLinkChain.containsScope(scope);
    }

    @TruffleBoundary
    public void registerScope(LLVMScope scope) {
        dynamicLinkChain.addScope(scope);
    }

    public synchronized void registerThread(LLVMThread thread) {
        assert !runningThreads.contains(thread);
        runningThreads.add(thread);
    }

    public synchronized void unregisterThread(LLVMThread thread) {
        runningThreads.remove(thread);
        assert !runningThreads.contains(thread);
    }

    @TruffleBoundary
    public synchronized void shutdownThreads() {
        // we need to iterate over a copy of the list, because stop() can modify the original list
        for (LLVMThread node : new ArrayList<>(runningThreads)) {
            node.stop();
        }
    }

    @TruffleBoundary
    public synchronized void awaitThreadTermination() {
        shutdownThreads();

        while (!runningThreads.isEmpty()) {
            LLVMThread node = runningThreads.get(0);
            node.awaitFinish();
            assert !runningThreads.contains(node); // should be unregistered by LLVMThreadNode
        }
    }

    public RootCallTarget[] getDestructorFunctions() {
        return destructorFunctions.toArray(new RootCallTarget[destructorFunctions.size()]);
    }

    public synchronized List<LLVMThread> getRunningThreads() {
        return Collections.unmodifiableList(runningThreads);
    }

    public LLVMSourceContext getSourceContext() {
        return sourceContext;
    }

    @TruffleBoundary
    public LLVMGlobal findGlobal(LLVMPointer pointer) {
        return globalsReverseMap.get(pointer);
    }

    @TruffleBoundary
    public void registerReadOnlyGlobals(LLVMPointer nonPointerStore, NodeFactory nodeFactory) {
        synchronized (globalsStoreLock) {
            initFreeGlobalBlocks(nodeFactory);
            globalsReadOnlyStore.add(nonPointerStore);
        }
    }

    @TruffleBoundary
    public void registerGlobals(LLVMPointer nonPointerStore, NodeFactory nodeFactory) {
        synchronized (globalsStoreLock) {
            initFreeGlobalBlocks(nodeFactory);
            globalsNonPointerStore.add(nonPointerStore);
        }
    }

    @TruffleBoundary
    public void registerGlobalReverseMap(LLVMGlobal global, LLVMPointer target) {
        globalsReverseMap.put(target, global);
    }

    public void setCleanupNecessary(boolean value) {
        cleanupNecessary = value;
    }

    @TruffleBoundary
    public LLVMInteropType getInteropType(LLVMSourceType sourceType) {
        return interopTypeRegistry.get(sourceType);
    }

    private void printNativeCallStatistics() {
        if (nativeCallStatistics != null) {
            LinkedHashMap<String, Integer> sorted = nativeCallStatistics.entrySet().stream().sorted(Map.Entry.comparingByValue()).collect(Collectors.toMap(
                            Map.Entry::getKey,
                            Map.Entry::getValue,
                            (e1, e2) -> e1,
                            LinkedHashMap::new));
            TargetStream stream = nativeCallStatsStream();
            for (String s : sorted.keySet()) {
                stream.printf("Function %s \t count: %d\n", s, sorted.get(s));
            }
        }
    }

    public LLVMPThreadContext getpThreadContext() {
        return pThreadContext;
    }

    private static class DynamicLinkChain {
        private final ArrayList<LLVMScope> scopes;

        DynamicLinkChain() {
            this.scopes = new ArrayList<>();
        }

        private void addScope(LLVMScope newScope) {
            assert !scopes.contains(newScope);
            scopes.add(newScope);
        }

        private boolean containsScope(LLVMScope scope) {
            return scopes.contains(scope);
        }
    }

    @CompilationFinal private TargetStream loaderTraceStream;
    @CompilationFinal private boolean loaderTraceStreamInitialized = false;

    public TargetStream loaderTraceStream() {
        if (!loaderTraceStreamInitialized) {
            CompilerDirectives.transferToInterpreterAndInvalidate();

            final String opt = env.getOptions().get(SulongEngineOption.LD_DEBUG);
            if (SulongEngineOption.optionEnabled(opt)) {
                loaderTraceStream = new TargetStream(env, opt);
            }

            loaderTraceStreamInitialized = true;
        }
        return loaderTraceStream;
    }

    @CompilationFinal private TargetStream syscallTraceStream;
    @CompilationFinal private boolean syscallTraceStreamInitialized = false;

    public TargetStream syscallTraceStream() {
        if (!syscallTraceStreamInitialized) {
            CompilerDirectives.transferToInterpreterAndInvalidate();

            final String opt = env.getOptions().get(SulongEngineOption.DEBUG_SYSCALLS);
            if (SulongEngineOption.optionEnabled(opt)) {
                syscallTraceStream = new TargetStream(env, opt);
            }

            syscallTraceStreamInitialized = true;
        }

        return syscallTraceStream;
    }

    @CompilationFinal private TargetStream nativeCallStatsStream;
    @CompilationFinal private boolean nativeCallStatsStreamInitialized = false;

    public TargetStream nativeCallStatsStream() {
        if (!nativeCallStatsStreamInitialized) {
            CompilerDirectives.transferToInterpreterAndInvalidate();

            final String opt = env.getOptions().get(SulongEngineOption.NATIVE_CALL_STATS);
            if (SulongEngineOption.optionEnabled(opt)) {
                nativeCallStatsStream = new TargetStream(env, opt);
            }

            nativeCallStatsStreamInitialized = true;
        }

        return nativeCallStatsStream;
    }

    @CompilationFinal private TargetStream lifetimeAnalysisStream;
    @CompilationFinal private boolean lifetimeAnalysisStreamInitialized = false;

    public TargetStream lifetimeAnalysisStream() {
        if (!lifetimeAnalysisStreamInitialized) {
            CompilerDirectives.transferToInterpreterAndInvalidate();

            final String opt = env.getOptions().get(SulongEngineOption.PRINT_LIFE_TIME_ANALYSIS_STATS);
            if (SulongEngineOption.optionEnabled(opt)) {
                lifetimeAnalysisStream = new TargetStream(env, opt);
            }

            lifetimeAnalysisStreamInitialized = true;
        }

        return lifetimeAnalysisStream;
    }

    @CompilationFinal private TargetStream llDebugVerboseStream;
    @CompilationFinal private boolean llDebugVerboseStreamInitialized = false;

    public TargetStream llDebugVerboseStream() {
        if (!llDebugVerboseStreamInitialized) {
            CompilerDirectives.transferToInterpreterAndInvalidate();

            final String opt = env.getOptions().get(SulongEngineOption.LL_DEBUG_VERBOSE);
            if (SulongEngineOption.optionEnabled(opt)) {
                if (!env.getOptions().get(SulongEngineOption.LL_DEBUG)) {
                    throw new IllegalStateException("\'--llvm.llDebug.verbose\' requires \'--llvm.llDebug=true\'");
                }
                llDebugVerboseStream = new TargetStream(env, opt);
            }

            llDebugVerboseStreamInitialized = true;
        }

        return llDebugVerboseStream;
    }

}
