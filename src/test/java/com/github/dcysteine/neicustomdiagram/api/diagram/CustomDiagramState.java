package com.github.dcysteine.neicustomdiagram.api.diagram;
import java.util.LinkedHashMap;
import java.util.Map;
public class CustomDiagramState extends DiagramState {
    protected Map<String, Object> stateMap = new LinkedHashMap<String, Object>();
    protected Object globalState;
    public Object state() { throw new AssertionError("No custom state supplier"); }
}
