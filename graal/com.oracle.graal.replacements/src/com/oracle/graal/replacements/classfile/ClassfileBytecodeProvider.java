/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.graal.replacements.classfile;

import static com.oracle.graal.compiler.common.util.Util.JAVA_SPECIFICATION_VERSION;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import com.oracle.graal.api.replacements.SnippetReflectionProvider;
import com.oracle.graal.bytecode.Bytecode;
import com.oracle.graal.bytecode.BytecodeProvider;
import com.oracle.graal.debug.GraalError;

import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;

/**
 * A {@link BytecodeProvider} that provide bytecode properties of a {@link ResolvedJavaMethod} as
 * parsed from a class file. This avoids all {@linkplain Instrumentation instrumentation} and any
 * bytecode rewriting performed by the VM.
 *
 * Since this class retrieves class file content based on existing {@link Class} instances, it
 * assumes the class files are well formed and thus does not perform any class file validation
 * checks.
 */
public class ClassfileBytecodeProvider implements BytecodeProvider {

    private final ClassLoader loader;
    private final Map<Class<?>, Classfile> classfiles = new HashMap<>();
    private final Map<String, Class<?>> classes = new HashMap<>();
    final MetaAccessProvider metaAccess;
    final SnippetReflectionProvider snippetReflection;

    public ClassfileBytecodeProvider(MetaAccessProvider metaAccess, SnippetReflectionProvider snippetReflection) {
        this.metaAccess = metaAccess;
        this.snippetReflection = snippetReflection;
        ClassLoader cl = getClass().getClassLoader();
        this.loader = cl == null ? ClassLoader.getSystemClassLoader() : cl;
    }

    @Override
    public Bytecode getBytecode(ResolvedJavaMethod method) {
        return getCodeAttributeFor(method);
    }

    /**
     * Determines if {@code name} and {@code descriptor} match {@code method}.
     */
    protected boolean matchesMethod(String name, String descriptor, ResolvedJavaMethod method) {
        return method.getName().equals(name) && method.getSignature().toMethodDescriptor().equals(descriptor);
    }

    /**
     * Determines if {@code name} and {@code descriptor} match {@code field}.
     */
    protected boolean matchesField(String name, String fieldType, ResolvedJavaField field) {
        return field.getName().equals(name) && field.getType().getName().equals(fieldType);
    }

    @Override
    public boolean supportsInvokedynamic() {
        return false;
    }

    // Use reflection so that this compiles on Java 8
    private static final Method getModule;
    private static final Method getResourceAsStream;
    static {
        if (JAVA_SPECIFICATION_VERSION >= 9) {
            try {
                getModule = Class.class.getMethod("getModule");
                getResourceAsStream = getModule.getReturnType().getMethod("getResourceAsStream", String.class);
            } catch (NoSuchMethodException | SecurityException e) {
                throw new GraalError(e);
            }
        } else {
            getModule = null;
            getResourceAsStream = null;
        }
    }

    private static InputStream getClassfileAsStream(Class<?> c) {
        String classfilePath = c.getName().replace('.', '/') + ".class";
        if (JAVA_SPECIFICATION_VERSION >= 9) {
            try {
                Object module = getModule.invoke(c);
                return (InputStream) getResourceAsStream.invoke(module, classfilePath);
            } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException | SecurityException e) {
                throw new GraalError(e);
            }
        } else {
            ClassLoader cl = c.getClassLoader();
            if (cl == null) {
                return ClassLoader.getSystemResourceAsStream(classfilePath);
            }
            return cl.getResourceAsStream(classfilePath);
        }
    }

    /**
     * Gets a {@link Classfile} created by parsing the class file bytes for {@code c}.
     *
     * @throws NoClassDefFoundError if the class file cannot be found
     */
    protected synchronized Classfile getClassfile(Class<?> c) {
        assert !c.isPrimitive() && !c.isArray() : c;
        Classfile classfile = classfiles.get(c);
        if (classfile == null) {
            try {
                ResolvedJavaType type = metaAccess.lookupJavaType(c);
                InputStream in = getClassfileAsStream(c);
                if (in != null) {
                    DataInputStream stream = new DataInputStream(in);
                    classfile = new Classfile(type, stream, this);
                    classfiles.put(c, classfile);
                    return classfile;
                }
                throw new NoClassDefFoundError(c.getName());
            } catch (IOException e) {
                throw (NoClassDefFoundError) new NoClassDefFoundError(c.getName()).initCause(e);
            }
        }
        return classfile;
    }

    /**
     * Gets a {@link ClassfileBytecode} representing the bytecode for {@code method} read from a
     * class file.
     */
    protected ClassfileBytecode getCodeAttributeFor(ResolvedJavaMethod method) {
        Classfile classfile = getClassfile(resolveToClass(method.getDeclaringClass().getName()));
        return classfile.getCode(this, method.getName(), method.getSignature().toMethodDescriptor());
    }

    protected synchronized Class<?> resolveToClass(String descriptor) {
        Class<?> c = classes.get(descriptor);
        if (c == null) {
            if (descriptor.length() == 1) {
                c = JavaKind.fromPrimitiveOrVoidTypeChar(descriptor.charAt(0)).toJavaClass();
            } else {
                int dimensions = 0;
                while (descriptor.charAt(dimensions) == '[') {
                    dimensions++;
                }
                String name;
                if (dimensions == 0 && descriptor.startsWith("L") && descriptor.endsWith(";")) {
                    name = descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
                } else {
                    name = descriptor.replace('/', '.');
                }
                try {
                    c = Class.forName(name, true, loader);
                    classes.put(descriptor, c);
                } catch (ClassNotFoundException e) {
                    throw new NoClassDefFoundError(descriptor);
                }
            }
        }
        return c;
    }

    protected ResolvedJavaMethod findMethod(ResolvedJavaType type, String name, String descriptor, boolean isStatic) {
        if (isStatic && name.equals("<clinit>")) {
            ResolvedJavaMethod method = type.getClassInitializer();
            if (method != null) {
                return method;
            }
        }
        ResolvedJavaMethod[] methodsToSearch = name.equals("<init>") ? type.getDeclaredConstructors() : type.getDeclaredMethods();
        for (ResolvedJavaMethod method : methodsToSearch) {
            if (method.isStatic() == isStatic && matchesMethod(name, descriptor, method)) {
                return method;
            }
        }
        return null;
    }

    protected ResolvedJavaField findField(ResolvedJavaType type, String name, String fieldType, boolean isStatic) {
        ResolvedJavaField[] fields = isStatic ? type.getStaticFields() : type.getInstanceFields(false);
        for (ResolvedJavaField field : fields) {
            if (matchesField(name, fieldType, field)) {
                return field;
            }
        }
        return null;
    }

}
