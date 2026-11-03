package org.qualet.irl.light.shadow;

/** Max-heap of stable SoA slot indices, used only after the pool fills. The
 *  caller rejects non-finite distances before writing the borrowed array.
 *  Slots themselves never move; smaller indices win equal-distance ties. */
final class FarthestCasterHeap
{
    private final float[] distances;
    private final int[] slots;
    private boolean ready;

    FarthestCasterHeap(float[] distances)
    {
        this.distances = distances;
        this.slots = new int[distances.length];
    }

    void reset() { ready = false; }

    /** Called AFTER the old farthest slot gets its nearer replacement. Avoid
     *  all heap maintenance for an unfilled pool or all-rejected candidates. */
    int afterReplace()
    {
        if (!ready)
        {
            for (int i = 0; i < slots.length; i++) slots[i] = i;
            for (int i = slots.length / 2 - 1; i >= 0; i--) siftDown(i);
            ready = true;
        }
        else
        {
            siftDown(0);
        }
        return slots[0];
    }

    private void siftDown(int parent)
    {
        int slot = slots[parent];
        int half = slots.length / 2;
        while (parent < half)
        {
            int child = parent * 2 + 1;
            if (child + 1 < slots.length && farther(slots[child + 1], slots[child])) child++;
            if (!farther(slots[child], slot)) break;
            slots[parent] = slots[child];
            parent = child;
        }
        slots[parent] = slot;
    }

    private boolean farther(int a, int b)
    {
        float da = distances[a], db = distances[b];
        // Use == rather than Float.compare: the existing strict-'>' scan
        // treats signed zero as a tie, then keeps the first slot.
        return da > db || (da == db && a < b);
    }
}
