package me.eigenraven.personalspace.gui;
public class WBlockDropdown extends Widget {
    public static boolean popup;
    private static WBlockDropdown openDropdown;
    private static net.minecraft.client.gui.GuiTextField searchField;
    private static java.util.List<Entry> filteredEntries = new java.util.ArrayList<Entry>();
    private final java.util.List<Entry> entries = new java.util.ArrayList<Entry>();
    private final int itemsPerRow=2,maxVisibleRows=2;
    private int scrollOffset,selectedIndex;
    private boolean addMode;
    public int callbacks;
    public WBlockDropdown(){for(int i=0;i<6;i++)entries.add(new Entry("block"+i));}
    public boolean isOpen(){return openDropdown==this;}
    public static boolean isAnyDropdownOpen() { return popup; }
    public java.util.List<Entry> getEntries(){return entries;}
    public int getSelectedIndex(){return selectedIndex;}
    public boolean mouseClicked(int x,int y,int button){super.mouseClicked(x,y,button);if(isOpen()){openDropdown=null;popup=false;}else{openDropdown=this;popup=true;scrollOffset=0;searchField=new net.minecraft.client.gui.GuiTextField(null,0,0,120,14);searchField.setMaxStringLength(256);updateFilteredEntries();}return true;}
    private static void updateFilteredEntries(){filteredEntries=new java.util.ArrayList<Entry>();for(Entry e:openDropdown.entries)if(e.blockName().contains(searchField.getText()))filteredEntries.add(e);}
    private static int[] computeDropdownPosition(WBlockDropdown dd,java.util.List<Entry> list){return new int[]{20,30};}
    public static boolean handleOpenDropdownScroll(int x,int y,int delta){openDropdown.scrollOffset+=delta<0?1:-1;return true;}
    public static boolean handleOpenDropdownClick(int x,int y,int button){int i=(openDropdown.scrollOffset+(y-30-16)/20)*2+(x-20)/20;Entry chosen=filteredEntries.get(i);openDropdown.selectedIndex=openDropdown.entries.indexOf(chosen);openDropdown.callbacks++;openDropdown=null;popup=false;return true;}
    public static boolean handleOpenDropdownMouseUp(){return true;}
    public static class Entry {private final String name;Entry(String n){name=n;}public String blockName(){return name;}public int meta(){return 0;}public String tooltip(){return name;}}
}
