package org.qualet.irl.light.shadow;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.LongPredicate;

/** CPU-only cache of completed live overlays. Membership compares caster identity
 * exactly, independently of collection order; unknown/incomplete draws never commit. */
public final class ShadowOverlayCache
{
    public record Member(int type, CasterRevision revision, float x, float y, float z,
                         float radius, float halfWidth, float halfHeight, int faces) {}

    public static final class Members
    {
        private final IdentityHashMap<Object, Member> values = new IdentityHashMap<>();
        private boolean known = true;

        public void clear() { clear(true); }

        /** Disabled collection still clears the previous light's membership. */
        public void clear(boolean enabled) { values.clear(); known = enabled; }

        /** Check completeness before allocating immutable member state. Once
         *  any caster is unknown, this entire overlay must draw again anyway. */
        public void add(Object caster, int type, CasterRevision revision,
                        float x, float y, float z, float radius,
                        float halfWidth, float halfHeight, int faces)
        {
            if (!known) return;
            if (caster == null || revision == null || !revision.known())
            {
                known = false;
                return;
            }
            add(caster, new Member(type, revision, x, y, z, radius, halfWidth, halfHeight, faces));
        }

        public void add(Object caster, Member member)
        {
            if (!known) return;
            if (caster == null || member.revision() == null || !member.revision().known()
                || values.put(caster, member) != null)
            {
                known = false;
            }
        }

        /** Ordinary spots have the same immutable member at each retained SoA
         *  slot throughout one bake. The caller clears bySlot BEFORE the next
         *  collect; old overlay snapshots may still own the immutable records.
         *  Point face masks and degenerate cones use the ordinary add path. */
        public void addSpot(Member[] bySlot, int slot, Object caster, int type,
                            CasterRevision revision, float x, float y, float z,
                            float radius, float halfWidth, float halfHeight)
        {
            if (!known) return;
            if (caster == null || revision == null || !revision.known())
            {
                known = false;
                return;
            }
            Member member = bySlot[slot];
            if (member == null)
            {
                member = new Member(type, revision, x, y, z, radius, halfWidth, halfHeight, 0);
                bySlot[slot] = member;
            }
            add(caster, member);
        }

        public boolean known() { return known && !values.isEmpty(); }
    }

    private record Entry(int tile, long light, Object blocks, long policy,
                         IdentityHashMap<Object, Member> members) {}
    private final Map<Long, Entry> entries = new HashMap<>();

    public boolean reusable(long id, int tile, long light, Object blocks, long policy, Members now)
    {
        Entry entry = entries.get(id);
        if (!now.known() || entry == null || entry.tile != tile || entry.light != light
            || entry.blocks != blocks || entry.policy != policy || entry.members.size() != now.values.size())
        {
            return false;
        }
        // IdentityHashMap.equals compares VALUES by identity too; compare immutable
        // member values explicitly so freshly sampled, equal revisions can hit.
        for (Map.Entry<Object, Member> member : now.values.entrySet())
        {
            if (!member.getValue().equals(entry.members.get(member.getKey()))) return false;
        }
        return true;
    }

    public void completed(long id, int tile, long light, Object blocks, long policy,
                          Members now, boolean success)
    {
        if (success && now.known())
            entries.put(id, new Entry(tile, light, blocks, policy, new IdentityHashMap<>(now.values)));
        else
            forget(id);
    }

    public void forget(long id) { entries.remove(id); }
    public void clear() { entries.clear(); }
    public void retain(LongPredicate keep) { entries.keySet().removeIf(id -> !keep.test(id)); }
}
