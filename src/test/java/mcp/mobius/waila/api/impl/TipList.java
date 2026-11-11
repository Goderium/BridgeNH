package mcp.mobius.waila.api.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import mcp.mobius.waila.api.ITaggedList;

/** Test-only Waila public tagged-list behavior; never included in the mod jar. */
public class TipList<E, T> extends ArrayList<E> implements ITaggedList<E, T> {
    private final Map<E, Set<T>> tags = new HashMap<>();
    public boolean add(E value, T tag) {
        tags.computeIfAbsent(value, key -> new HashSet<>()).add(tag);
        return super.add(value);
    }
    public boolean add(E value, Collection<? extends T> values) {
        tags.computeIfAbsent(value, key -> new HashSet<>()).addAll(values);
        return super.add(value);
    }
    public Set<T> getTags(E value) { return contains(value) ? tags.computeIfAbsent(value, key -> new HashSet<>()) : null; }
    public Set<T> getTags(int index) { return getTags(get(index)); }
    public void addTag(E value, T tag) { getTags(value).add(tag); }
    public void addTag(int index, T tag) { addTag(get(index), tag); }
    public void removeTag(E value, T tag) { getTags(value).remove(tag); }
    public void removeTag(int index, T tag) { removeTag(get(index), tag); }
    public Set<E> getEntries(T tag) {
        Set<E> values = new HashSet<>();
        for (Map.Entry<E, Set<T>> entry : tags.entrySet()) if (entry.getValue().contains(tag)) values.add(entry.getKey());
        return values;
    }
}
