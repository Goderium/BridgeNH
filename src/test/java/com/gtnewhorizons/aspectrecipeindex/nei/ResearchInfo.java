package com.gtnewhorizons.aspectrecipeindex.nei;
import java.util.List;
public class ResearchInfo {
    private boolean isResearched;
    private String category, research;
    public final List<String> fixtureLines;
    public ResearchInfo(String category, String research, List<String> lines) { this.category = category; this.research = research; fixtureLines = lines; }
    public void fixtureName(String name) { research = name; }
    public void onHover(List<String> lines) { for (String line : fixtureLines) lines.add(line); if (lines.size() > 1) lines.add(1, ""); }
}
