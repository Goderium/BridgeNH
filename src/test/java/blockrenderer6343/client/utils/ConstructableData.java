package blockrenderer6343.client.utils;

import java.util.LinkedHashMap;
import java.util.Map;

public class ConstructableData {
    public final Map<String, Integer> fixtureChannels = new LinkedHashMap<>();
    public Map<String, Integer> getChannelMaxTierMap() { return fixtureChannels; }
}
