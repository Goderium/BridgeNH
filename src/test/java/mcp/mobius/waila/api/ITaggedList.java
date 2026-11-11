package mcp.mobius.waila.api;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/** Public Waila 1.19.34 tagged-list signatures used by provider fixtures. */
public interface ITaggedList<E, T> extends List<E> {
    boolean add(E value, T tag);
    boolean add(E value, Collection<? extends T> tags);
    Set<T> getTags(E value);
    Set<T> getTags(int index);
    void addTag(E value, T tag);
    void addTag(int index, T tag);
    void removeTag(E value, T tag);
    void removeTag(int index, T tag);
    Set<E> getEntries(T tag);
}
