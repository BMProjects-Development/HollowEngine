package ru.hollowhorizon.hollowengine.bootstrap.impl.mixins;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;
import ru.hollowhorizon.hollowengine.bootstrap.runtime.mixins.ScriptMixinDispatch;
import ru.hollowhorizon.hollowengine.bootstrap.runtime.mixins.ScriptMixinSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the handlers of one script into mixin classes, one per target class.
 * <p>
 * A generated handler holds no script code. It packs its arguments and calls {@link ScriptMixinDispatch}
 * with the slot reserved for it, so all Mixin ever sees is a small Java-shaped class it fully understands.
 */
final class ScriptMixinGenerator {
    private static final String DISPATCH = Type.getInternalName(ScriptMixinDispatch.class);
    private static final String OBJECT = "java/lang/Object";
    private static final Type OBJECT_TYPE = Type.getObjectType(OBJECT);
    private static final Method MODIFY_DISPATCH = new Method("modify", OBJECT_TYPE, new Type[]{Type.INT_TYPE, OBJECT_TYPE, Type.getType(Object[].class), OBJECT_TYPE});
    private static final Type CALLBACK_INFO = Type.getObjectType("org/spongepowered/asm/mixin/injection/callback/CallbackInfo");
    private static final Method INJECT_DISPATCH = new Method("inject", Type.VOID_TYPE, new Type[]{Type.INT_TYPE, OBJECT_TYPE, Type.getType(Object[].class), CALLBACK_INFO});
    private static final Type CALLBACK_INFO_RETURNABLE = Type.getObjectType("org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable");
    private static final Type OPERATION = Type.getObjectType("com/llamalad7/mixinextras/injector/wrapoperation/Operation");
    private static final Method WRAP_DISPATCH = new Method("wrap", OBJECT_TYPE, new Type[]{Type.INT_TYPE, OBJECT_TYPE, Type.getType(Object[].class), OPERATION});
    private static final Method WRAP_CALL_DISPATCH = new Method("wrapCall", OBJECT_TYPE, new Type[]{Type.INT_TYPE, OBJECT_TYPE, OBJECT_TYPE, Type.BOOLEAN_TYPE, Type.getType(Object[].class), OPERATION});
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String AT = "Lorg/spongepowered/asm/mixin/injection/At;";
    private static final String SHIFT = "Lorg/spongepowered/asm/mixin/injection/At$Shift;";
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String MODIFY_RETURN_VALUE = "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;";
    private static final String MODIFY_EXPRESSION_VALUE = "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;";
    private static final String WRAP_OPERATION = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
    private static final String WRAP_METHOD = "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;";

    private ScriptMixinGenerator() {
    }

    /**
     * Mixin classes for {@code handlers}, named {@code <packagePrefix><baseName>$<n>}, keyed by internal
     * name. Slots are reserved in {@link ScriptMixinDispatch} as a side effect.
     */
    static Map<String, byte[]> generate(String packagePrefix, String baseName, List<ScriptMixinHandler> handlers) {
        Map<String, List<ScriptMixinHandler>> byTarget = new LinkedHashMap<>();
        for (ScriptMixinHandler handler : handlers) {
            validate(handler);
            byTarget.computeIfAbsent(handler.target().owner(), owner -> new ArrayList<>()).add(handler);
        }

        Map<String, byte[]> classes = new LinkedHashMap<>();
        int index = 0;
        for (Map.Entry<String, List<ScriptMixinHandler>> entry : byTarget.entrySet()) {
            String name = packagePrefix.replace('.', '/') + baseName + "$" + index++;
            classes.put(name, generateClass(name, entry.getKey(), entry.getValue()));
        }
        return classes;
    }

    private static void validate(ScriptMixinHandler handler) {
        ScriptMixinHandler.Member target = handler.target();
        if (target.opcode() == Opcodes.INVOKEINTERFACE) {
            throw new IllegalArgumentException(handler.key() + ": " + target.owner() + " is an interface, which script mixins cannot target");
        }
        ScriptMixinHandler.Member at = handler.atTarget();
        switch (handler.kind()) {
            case MODIFY_RETURN_VALUE -> {
                if (Type.getReturnType(target.descriptor()) == Type.VOID_TYPE) {
                    throw new IllegalArgumentException(handler.key() + ": " + target.name() + " returns nothing to modify");
                }
            }
            case MODIFY_EXPRESSION_VALUE -> {
                if (at == null || at.opcode() == Opcodes.PUTFIELD || at.opcode() == Opcodes.PUTSTATIC || at.valueType() == Type.VOID_TYPE) {
                    throw new IllegalArgumentException(handler.key() + ": the point has no value to modify");
                }
            }
            case WRAP_OPERATION -> {
                if (at == null || at.isField()) {
                    throw new IllegalArgumentException(handler.key() + ": only a call can be wrapped");
                }
            }
            default -> {
            }
        }
    }

    private static byte[] generateClass(String name, String target, List<ScriptMixinHandler> handlers) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, OBJECT, null);

        AnnotationVisitor mixin = writer.visitAnnotation(MIXIN, false);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType(target));
        targets.visitEnd();
        mixin.visit("remap", false);
        mixin.visitEnd();

        for (int index = 0; index < handlers.size(); index++) {
            generateHandler(writer, "hollowengine$script$" + index, handlers.get(index));
        }

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void generateHandler(ClassWriter writer, String name, ScriptMixinHandler handler) {
        ScriptMixinHandler.Member target = handler.target();
        boolean isStatic = target.isStatic();
        Type[] targetArguments = Type.getArgumentTypes(target.descriptor());
        Type targetReturn = Type.getReturnType(target.descriptor());

        Method method = switch (handler.kind()) {
            case INJECT ->
                    new Method(name, Type.VOID_TYPE, append(targetArguments, targetReturn == Type.VOID_TYPE ? CALLBACK_INFO : CALLBACK_INFO_RETURNABLE));
            case MODIFY_RETURN_VALUE -> new Method(name, targetReturn, new Type[]{targetReturn});
            case MODIFY_EXPRESSION_VALUE -> {
                Type value = handler.atTarget().valueType();
                yield new Method(name, value, new Type[]{value});
            }
            case WRAP_OPERATION -> {
                ScriptMixinHandler.Member call = handler.atTarget();
                Type[] callArguments = Type.getArgumentTypes(call.descriptor());
                Type[] operands = call.isStatic() ? callArguments : prepend(Type.getObjectType(call.owner()), callArguments);
                yield new Method(name, Type.getReturnType(call.descriptor()), append(operands, OPERATION));
            }
            case WRAP_METHOD -> new Method(name, targetReturn, append(targetArguments, OPERATION));
        };

        int access = Opcodes.ACC_PRIVATE | (isStatic ? Opcodes.ACC_STATIC : 0);
        GeneratorAdapter code = new GeneratorAdapter(access, method, null, null, writer);
        annotate(code, handler);
        code.visitCode();

        int argumentCount = method.getArgumentTypes().length;
        code.push(ScriptMixinDispatch.slot(handler.key()));
        if (isStatic) code.visitInsn(Opcodes.ACONST_NULL);
        else code.loadThis();

        switch (handler.kind()) {
            case INJECT -> {
                loadArgumentArray(code, method, argumentCount - 1);
                code.loadArg(argumentCount - 1);
                code.invokeStatic(Type.getObjectType(DISPATCH), INJECT_DISPATCH);
            }
            case MODIFY_RETURN_VALUE, MODIFY_EXPRESSION_VALUE -> {
                loadArgumentArray(code, method, 0);
                code.loadArg(0);
                code.valueOf(method.getArgumentTypes()[0]);
                code.invokeStatic(Type.getObjectType(DISPATCH), MODIFY_DISPATCH);
                code.unbox(method.getReturnType());
            }
            case WRAP_OPERATION -> {
                boolean hasReceiver = !handler.atTarget().isStatic();
                if (hasReceiver) code.loadArg(0);
                else code.visitInsn(Opcodes.ACONST_NULL);
                code.push(hasReceiver);
                int first = hasReceiver ? 1 : 0;
                loadArgumentArray(code, method, first, argumentCount - 1);
                code.loadArg(argumentCount - 1);
                code.invokeStatic(Type.getObjectType(DISPATCH), WRAP_CALL_DISPATCH);
                if (method.getReturnType() == Type.VOID_TYPE) code.pop();
                else code.unbox(method.getReturnType());
            }
            case WRAP_METHOD -> {
                loadArgumentArray(code, method, argumentCount - 1);
                code.loadArg(argumentCount - 1);
                code.invokeStatic(Type.getObjectType(DISPATCH), WRAP_DISPATCH);
                if (method.getReturnType() == Type.VOID_TYPE) code.pop();
                else code.unbox(method.getReturnType());
            }
        }

        code.returnValue();
        code.endMethod();
    }

    /**
     * Boxes the first {@code count} arguments of {@code method} into a new {@code Object[]}.
     */
    private static void loadArgumentArray(GeneratorAdapter code, Method method, int count) {
        loadArgumentArray(code, method, 0, count);
    }

    /**
     * Boxes arguments {@code from} (inclusive) to {@code to} (exclusive) into a new {@code Object[]}.
     */
    private static void loadArgumentArray(GeneratorAdapter code, Method method, int from, int to) {
        Type[] arguments = method.getArgumentTypes();
        code.push(to - from);
        code.newArray(OBJECT_TYPE);
        for (int index = from; index < to; index++) {
            code.dup();
            code.push(index - from);
            code.loadArg(index);
            code.valueOf(arguments[index]);
            code.arrayStore(OBJECT_TYPE);
        }
    }

    private static void annotate(GeneratorAdapter code, ScriptMixinHandler handler) {
        String descriptor = switch (handler.kind()) {
            case INJECT -> INJECT;
            case MODIFY_RETURN_VALUE -> MODIFY_RETURN_VALUE;
            case MODIFY_EXPRESSION_VALUE -> MODIFY_EXPRESSION_VALUE;
            case WRAP_OPERATION -> WRAP_OPERATION;
            case WRAP_METHOD -> WRAP_METHOD;
        };
        AnnotationVisitor annotation = code.visitAnnotation(descriptor, true);
        AnnotationVisitor methods = annotation.visitArray("method");
        methods.visit(null, handler.target().selector());
        methods.visitEnd();
        annotation.visit("remap", false);

        if (handler.kind() != ScriptMixinSpec.Kind.WRAP_METHOD) {
            AnnotationVisitor points = annotation.visitArray("at");
            visitAt(points.visitAnnotation(null, AT), handler);
            points.visitEnd();
        }
        if (handler.kind() == ScriptMixinSpec.Kind.INJECT) {
            annotation.visit("cancellable", true);
        }
        annotation.visitEnd();
    }

    private static void visitAt(AnnotationVisitor at, ScriptMixinHandler handler) {
        at.visit("value", handler.point().name());
        at.visit("remap", false);
        ScriptMixinHandler.Member member = handler.atTarget();
        if (member != null) {
            at.visit("target", member.qualified());
            if (member.isField()) at.visit("opcode", member.opcode());
        }
        if (handler.ordinal() >= 0) at.visit("ordinal", handler.ordinal());
        if (handler.shift() == ScriptMixinSpec.Shift.AFTER) at.visitEnum("shift", SHIFT, "AFTER");
        at.visitEnd();
    }

    private static Type[] append(Type[] types, Type last) {
        Type[] result = new Type[types.length + 1];
        System.arraycopy(types, 0, result, 0, types.length);
        result[types.length] = last;
        return result;
    }

    private static Type[] prepend(Type first, Type[] types) {
        Type[] result = new Type[types.length + 1];
        result[0] = first;
        System.arraycopy(types, 0, result, 1, types.length);
        return result;
    }
}
