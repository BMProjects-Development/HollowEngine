package ru.hollowhorizon.hollowengine.bootstrap.impl.mixins;

import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import ru.hollowhorizon.hollowengine.bootstrap.runtime.mixins.ScriptMixinSpec;

/**
 * One handler of a mixin script, in the names the game runs with.
 *
 * @param key      slot key, already qualified with the script id
 * @param target   the method the handler goes into
 * @param atTarget the call or field the injection point refers to, for {@code INVOKE} and {@code FIELD}
 */
record ScriptMixinHandler(
        String key,
        ScriptMixinSpec.Kind kind,
        Member target,
        ScriptMixinSpec.Point point,
        @Nullable Member atTarget,
        int ordinal,
        ScriptMixinSpec.Shift shift
) {
    /** A method or field reference as it was written into the spec, with the opcode that referenced it. */
    record Member(int opcode, String owner, String name, String descriptor) {
        boolean isStatic() {
            return opcode == Opcodes.INVOKESTATIC || opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC;
        }

        boolean isField() {
            return opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC
                    || opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC;
        }

        /** The selector Mixin expects in {@code method}: name and descriptor, without the owner. */
        String selector() {
            return name + descriptor;
        }

        /** The fully qualified form Mixin expects in {@code @At.target}. */
        String qualified() {
            String ownerDescriptor = Type.getObjectType(owner).getDescriptor();
            return isField() ? ownerDescriptor + name + ":" + descriptor : ownerDescriptor + name + descriptor;
        }

        /** Type of the value this member produces: a method's return type, or a field's type. */
        Type valueType() {
            return isField() ? Type.getType(descriptor) : Type.getReturnType(descriptor);
        }
    }
}
