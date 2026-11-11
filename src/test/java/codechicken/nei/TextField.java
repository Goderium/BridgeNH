package codechicken.nei;
public class TextField extends Widget {
    public String value = "fixture search";
    public String text() { return value; }
    public int edits;
    public void setText(String text) { edits++; value = text; }
}
