package appeng.util;
import java.util.HashMap;
import java.util.Map;
public class AEStackTypeFilter {
    public final Map<Object, Boolean> values = new HashMap<Object, Boolean>();
    public AEStackTypeFilter() { }
    public AEStackTypeFilter(AEStackTypeFilter other) { values.putAll(other.values); }
    public Map<Object, Boolean> getFiltersMap() { return values; }
    public Map<Object, Boolean> getImmutableFilters() { return new HashMap<Object, Boolean>(values); }
    public void setEnabled(Object type, boolean enabled) { values.put(type, enabled); }
    public void copyFrom(AEStackTypeFilter other) { values.clear(); values.putAll(other.values); }
}
