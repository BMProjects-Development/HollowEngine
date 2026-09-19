package ru.hollowhorizon.hollowengine.bootstrap.impl.mixins;

import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import ru.hollowhorizon.hollowengine.bootstrap.runtime.mixins.ScriptMixinSpec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the {@link ScriptMixinSpec} class of one script.
 */
final class ScriptMixinSpecReader {
    private ScriptMixinSpecReader() {
    }

    static Spec read(String scriptId, byte[] spec) {
        ClassNode node = new ClassNode();
        new ClassReader(spec).accept(node, ClassReader.SKIP_FRAMES);

        Map<String, Object> header = values(find(node.invisibleAnnotations, ScriptMixinSpec.SPEC_ANNOTATION));
        int version = (Integer) header.getOrDefault("version", -1);
        if (version != ScriptMixinSpec.VERSION) {
            throw new IllegalArgumentException("Mixin spec of '" + scriptId + "' has version " + version + ", this engine reads " + ScriptMixinSpec.VERSION + "; recompile the script");
        }

        List<ScriptMixinHandler> handlers = new ArrayList<>();
        for (MethodNode method : node.methods) {
            AnnotationNode annotation = find(method.invisibleAnnotations, ScriptMixinSpec.HANDLER_ANNOTATION);
            if (annotation == null) continue;
            handlers.add(handler(scriptId, method, values(annotation)));
        }
        return new Spec(Boolean.TRUE.equals(header.get("clientOnly")), handlers);
    }

    private static ScriptMixinHandler handler(String scriptId, MethodNode method, Map<String, Object> values) {
        List<ScriptMixinHandler.Member> members = getMembers(method);

        ScriptMixinSpec.Point point = ScriptMixinSpec.Point.valueOf((String) values.get("point"));
        int expected = point.hasTarget() ? 2 : 1;
        if (members.size() != expected) {
            throw new IllegalArgumentException("Handler " + method.name + " of '" + scriptId + "' refers to " + members.size() + " members, expected " + expected);
        }

        return new ScriptMixinHandler(ScriptMixinSpec.qualifiedKey(scriptId, (String) values.get("key")), ScriptMixinSpec.Kind.valueOf((String) values.get("kind")), members.get(0), point, point.hasTarget() ? members.get(1) : null, (Integer) values.getOrDefault("ordinal", -1), ScriptMixinSpec.Shift.valueOf((String) values.getOrDefault("shift", ScriptMixinSpec.Shift.BEFORE.name())));
    }

    private static @NotNull List<ScriptMixinHandler.Member> getMembers(MethodNode method) {
        List<ScriptMixinHandler.Member> members = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                members.add(new ScriptMixinHandler.Member(call.getOpcode(), call.owner, call.name, call.desc));
            } else if (instruction instanceof FieldInsnNode field) {
                members.add(new ScriptMixinHandler.Member(field.getOpcode(), field.owner, field.name, field.desc));
            }
        }
        return members;
    }

    private static AnnotationNode find(List<AnnotationNode> annotations, String descriptor) {
        if (annotations == null) return null;
        for (AnnotationNode annotation : annotations) {
            if (annotation.desc.equals(descriptor)) return annotation;
        }
        return null;
    }

    private static Map<String, Object> values(AnnotationNode annotation) {
        Map<String, Object> values = new HashMap<>();
        if (annotation == null || annotation.values == null) return values;
        for (int index = 0; index + 1 < annotation.values.size(); index += 2) {
            values.put((String) annotation.values.get(index), annotation.values.get(index + 1));
        }
        return values;
    }

    /**
     * What one script asks for. A client-only script's mixins are left out on a dedicated server.
     */
    record Spec(boolean clientOnly, List<ScriptMixinHandler> handlers) {
    }
}
