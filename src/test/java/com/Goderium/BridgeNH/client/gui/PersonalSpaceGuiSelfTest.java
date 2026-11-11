package com.Goderium.BridgeNH.client.gui;

import com.Goderium.BridgeNH.util.BridgeException;
import com.google.gson.JsonObject;
import java.util.Collections;
import me.eigenraven.personalspace.gui.*;

/** Mock widget contract only; live optional-mod/native packet acceptance is separate. */
public final class PersonalSpaceGuiSelfTest {
    private static int checks;
    public static int run() {
        checks=0; GuiControlAdapter.invalidate(); GuiEditWorld screen=new GuiEditWorld();
        check(!PersonalSpaceGuiAdapter.screen(new Object()),"optional mod recognition is isolated");
        check(PersonalSpaceGuiAdapter.pending(screen),"save initially requires an observed native frame");
        PersonalSpaceGuiAdapter.rendered(screen);
        JsonObject page=GuiControlAdapter.read(screen,args(),Collections.emptyList(),System.nanoTime()+1000000000L);
        check(!page.get("scanTruncated").getAsBoolean(),"public tree completes without scanning model collections");
        String toggle=id(page,"enableWeather"),save=id(page,"save"),text=id(page,"presetEntry"),hidden=id(page,"hidden"),slider=id(page,"skyRed");
        JsonObject selected=action(toggle,"set_selected"); selected.addProperty("selected",1);
        GuiControlAdapter.perform(screen,selected,Collections.emptyList());
        check(screen.enableWeather.value&&screen.enableWeather.presses==1&&screen.enableWeather.releases==1,"toggle invokes one native press and release");
        GuiControlAdapter.perform(screen,selected,Collections.emptyList());check(screen.enableWeather.presses==1,"idempotent toggle does not flip twice");
        expect("not_ready",()->GuiControlAdapter.perform(screen,action(save,"activate"),Collections.emptyList()));
        expect("disabled",()->GuiControlAdapter.perform(screen,action(hidden,"activate"),Collections.emptyList()));
        JsonObject edit=action(text,"set_text"); edit.addProperty("text","2;7,2x3,2;1;"); edit.addProperty("expectedText","");
        JsonObject receipt=GuiControlAdapter.perform(screen,edit,Collections.emptyList());
        check(!receipt.get("nativeCommitInvoked").getAsBoolean()&&screen.presetEntry.textField.getText().equals("2;7,2x3,2;1;"),"text receipt does not invent server commit");
        check(screen.presetEntry.textField.isFocused()&&PersonalSpaceGuiAdapter.pending(screen),"committed edit keeps focus until the mod's natural parse frame");
        JsonObject blur=action(text,"focus");blur.addProperty("focused",false);expect("not_ready",()->GuiControlAdapter.perform(screen,blur,Collections.emptyList()));
        expect("value_changed",()->GuiControlAdapter.perform(screen,edit,Collections.emptyList()));
        JsonObject value=action(slider,"set_value");value.addProperty("value",128);
        GuiControlAdapter.perform(screen,value,Collections.emptyList());check(screen.skyRed.presses==1&&screen.skyRed.releases==1&&Math.abs(screen.skyRed.getValue()-128)<=2,"slider uses quantized native pixels and releases");
        WBlockDropdown.popup=true;expect("unsupported",()->GuiControlAdapter.perform(screen,action(toggle,"activate"),Collections.emptyList()));WBlockDropdown.popup=false;
        PersonalSpaceGuiAdapter.rendered(screen);check(!screen.presetEntry.textField.isFocused(),"native parse completion releases deferred text focus");GuiControlAdapter.perform(screen,action(save,"activate"),Collections.emptyList());check(screen.save.presses==1,"completed natural frame permits save callback");
        screen.page1Container.children.remove(screen.enableWeather);expect("stale_widget",()->GuiControlAdapter.perform(screen,selected,Collections.emptyList()));
        GuiEditWorld other=new GuiEditWorld();check(PersonalSpaceGuiAdapter.pending(other),"another screen cannot inherit frame confirmation");
        String dropdown=id(page,"boundaryBlockADropdown");
        JsonObject search=action(dropdown,"set_text");search.addProperty("text","block5");
        receipt=GuiControlAdapter.perform(screen,search,Collections.emptyList());
        check(receipt.get("localTextMatchesRequested").getAsBoolean()&&receipt.getAsJsonObject("after").get("filteredCount").getAsInt()==1,"native whole search replacement refreshes actual filtered options");
        JsonObject pick=action(dropdown,"set_selected");pick.addProperty("selected",5);
        GuiControlAdapter.perform(screen,pick,Collections.emptyList());check(screen.boundaryBlockADropdown.getSelectedIndex()==5&&screen.boundaryBlockADropdown.callbacks==1&&!screen.boundaryBlockADropdown.isOpen(),"filtered original index invokes one native selection callback and closes");
        GuiControlAdapter.perform(screen,pick,Collections.emptyList());check(screen.boundaryBlockADropdown.callbacks==2,"far unfiltered option scrolls through native callback before one selection");
        GuiControlAdapter.invalidate();return checks;
    }
    private static JsonObject args(){JsonObject a=new JsonObject();a.addProperty("screenId","personalspace-fixture");a.addProperty("limit",128);return a;}
    private static JsonObject action(String id,String op){JsonObject a=new JsonObject();a.addProperty("screenId","personalspace-fixture");a.addProperty("widgetId",id);a.addProperty("action",op);return a;}
    private static String id(JsonObject page,String name){for(com.google.gson.JsonElement e:page.getAsJsonArray("widgets"))if(name.equals(e.getAsJsonObject().get("name").getAsString()))return e.getAsJsonObject().get("widgetId").getAsString();throw new AssertionError(name);}
    private static void check(boolean pass,String message){checks++;if(!pass)throw new AssertionError(message);}
    private static void expect(String code,Runnable action){try{action.run();throw new AssertionError("Expected "+code);}catch(BridgeException e){check(code.equals(e.code),"Expected "+code+", got "+e.code);}}
}
