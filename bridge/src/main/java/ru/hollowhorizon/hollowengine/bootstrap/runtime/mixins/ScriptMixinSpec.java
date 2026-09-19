package ru.hollowhorizon.hollowengine.bootstrap.runtime.mixins;

/**
 * Layout of the class a compiled mixin script carries next to its own classes, describing what to generate.
 * <p>
 * Every handler is a static method annotated with {@link #HANDLER_ANNOTATION}. Its body is never run: it
 * holds the target method, and for a call or field injection point the member the point refers to, as real
 * instructions. That is what lets the ordinary script remapping carry these references into the names the
 * game runs with, with no mixin-specific step anywhere between the compiler and the generator.
 */
public final class ScriptMixinSpec {
    /** Bumped whenever the layout changes in a way an older generator cannot read. */
    public static final int VERSION = 1;

    /** Appended to the binary name of the script class. */
    public static final String CLASS_SUFFIX = "$HollowMixins";

    /** On the spec class: {@code version} (int) and {@code clientOnly} (boolean). */
    public static final String SPEC_ANNOTATION = "Lru/hollowhorizon/hollowengine/mixin/Spec;";

    /**
     * On each handler method: {@code kind}, {@code key}, {@code point}, {@code shift} (strings) and
     * {@code ordinal} (int).
     */
    public static final String HANDLER_ANNOTATION = "Lru/hollowhorizon/hollowengine/mixin/Handler;";

    private ScriptMixinSpec() {
    }

    /** Joins the id of a script with the key of one of its handlers into the key a slot is bound by. */
    public static String qualifiedKey(String scriptId, String localKey) {
        return scriptId + "/" + localKey;
    }

    public enum Kind {
        /** {@code @Inject}: a body that runs at a point and may cancel the method. */
        INJECT,

        /** MixinExtras {@code @ModifyReturnValue}. */
        MODIFY_RETURN_VALUE,

        /** MixinExtras {@code @ModifyExpressionValue} on the result of a call or a field read. */
        MODIFY_EXPRESSION_VALUE,

        /** MixinExtras {@code @WrapOperation} around a call. */
        WRAP_OPERATION,

        /** MixinExtras {@code @WrapMethod} around the whole method. */
        WRAP_METHOD
    }

    public enum Point {
        HEAD,
        RETURN,
        TAIL,
        INVOKE,
        FIELD;

        /** Whether the point names a member, which the spec then carries as a second instruction. */
        public boolean hasTarget() {
            return this == INVOKE || this == FIELD;
        }
    }

    public enum Shift {
        BEFORE,
        AFTER
    }
}
