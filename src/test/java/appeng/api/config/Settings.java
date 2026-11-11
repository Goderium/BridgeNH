package appeng.api.config;

import java.util.Arrays;
import java.util.List;

public enum Settings {
    SORT_BY, SORT_DIRECTION, VIEW_MODE;
    public enum Option { NAME, SIZE, ASCENDING, DESCENDING, ALL, CRAFTABLE }
    public List<Option> getPossibleValues() { return Arrays.asList(Option.values()); }
}
