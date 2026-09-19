package ru.hollowhorizon.hollowengine.bootstrap.runtime.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * The only code a generated script mixin runs. Every handler forwards here with its slot number.
 * <p>
 * Mixin classes are applied in the game's classloader, while script bodies are Kotlin lambdas that live in
 * the isolated runtime, so a handler cannot call a body directly. Slots are numbered while the mixin
 * classes are generated, before any script runs; a script binds its bodies to them by key later and may
 * rebind them when it is reloaded. A slot nobody bound behaves as if the mixin were not there.
 */
public final class ScriptMixinDispatch {
    private static final Map<String, Integer> SLOTS = new HashMap<>();
    private static volatile Handler[] handlers = new Handler[0];

    private ScriptMixinDispatch() {
    }

    /**
     * A script body. {@code context} is the {@link CallbackInfo} of an injection, the original value of a
     * modification, or an {@link Original} for a wrapper.
     */
    @FunctionalInterface
    public interface Handler {
        Object handle(Object self, Object[] args, Object context) throws Throwable;
    }

    /** The wrapped method, as a wrapper body sees it. */
    @FunctionalInterface
    public interface Original {
        Object call(Object[] args);
    }

    /** The wrapped call, as a wrapper body sees it. {@code receiver} is ignored for a static call. */
    public interface Call {
        Object receiver();

        Object proceed(Object receiver, Object[] args);
    }

    /** Reserves the slot of a generated handler. Called once per handler, while mixins are prepared. */
    public static synchronized int slot(String key) {
        Integer existing = SLOTS.get(key);
        if (existing != null) return existing;
        int slot = SLOTS.size();
        SLOTS.put(key, slot);
        handlers = Arrays.copyOf(handlers, slot + 1);
        return slot;
    }

    /** Whether a handler with this key was generated during this launch. */
    public static synchronized boolean isApplied(String key) {
        return SLOTS.containsKey(key);
    }

    /** Binds {@code handler} to the slot of {@code key}. Returns {@code false} when no such slot exists. */
    public static synchronized boolean bind(String key, Handler handler) {
        Integer slot = SLOTS.get(key);
        if (slot == null) return false;
        Handler[] updated = handlers.clone();
        updated[slot] = handler;
        handlers = updated;
        return true;
    }

    public static synchronized void unbind(String key) {
        Integer slot = SLOTS.get(key);
        if (slot == null) return;
        Handler[] updated = handlers.clone();
        updated[slot] = null;
        handlers = updated;
    }

    public static void inject(int slot, Object self, Object[] args, CallbackInfo info) {
        Handler handler = handlers[slot];
        if (handler == null) return;
        invoke(handler, self, args, info);
    }

    public static Object modify(int slot, Object self, Object[] args, Object original) {
        Handler handler = handlers[slot];
        if (handler == null) return original;
        return invoke(handler, self, args, original);
    }

    public static Object wrap(int slot, Object self, Object[] args, Operation<?> operation) {
        Handler handler = handlers[slot];
        if (handler == null) return operation.call(args);
        return invoke(handler, self, args, (Original) operation::call);
    }

    public static Object wrapCall(int slot, Object self, Object receiver, boolean hasReceiver, Object[] args, Operation<?> operation) {
        Handler handler = handlers[slot];
        if (handler == null) return operation.call(operands(receiver, hasReceiver, args));
        return invoke(handler, self, args, new Call() {
            @Override
            public Object receiver() {
                return receiver;
            }

            @Override
            public Object proceed(Object newReceiver, Object[] newArgs) {
                return operation.call(operands(newReceiver, hasReceiver, newArgs));
            }
        });
    }

    /** What {@link Operation#call} expects: the receiver of a non-static call goes first. */
    private static Object[] operands(Object receiver, boolean hasReceiver, Object[] args) {
        if (!hasReceiver) return args;
        Object[] operands = new Object[args.length + 1];
        operands[0] = receiver;
        System.arraycopy(args, 0, operands, 1, args.length);
        return operands;
    }

    private static Object invoke(Handler handler, Object self, Object[] args, Object context) {
        try {
            return handler.handle(self, args, context);
        } catch (Throwable throwable) {
            throw ScriptMixinDispatch.<RuntimeException>sneakyThrow(throwable);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T sneakyThrow(Throwable throwable) throws T {
        throw (T) throwable;
    }
}
