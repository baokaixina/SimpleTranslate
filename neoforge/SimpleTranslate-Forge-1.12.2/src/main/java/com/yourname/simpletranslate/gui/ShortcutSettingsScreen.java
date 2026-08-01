package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.KeyChord;
import com.yourname.simpletranslate.keybind.ShortcutAction;
import com.yourname.simpletranslate.translation.TranslationEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiControls;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Exact-modifier keyboard/mouse chord editor plus vanilla Controls link. */
final class ShortcutSettingsScreen extends ScrollableSettingsScreen {
    private final List<HoldOriginalFeature> holdFeatures=new ArrayList<HoldOriginalFeature>();
    private ShortcutAction captureAction; private HoldOriginalFeature captureHold; private String status="";
    ShortcutSettingsScreen(GuiScreen parent,TranslationEngine engine){super(parent,engine,"screen.simple_translate.shortcuts","screen.simple_translate.main.shortcuts");for(HoldOriginalFeature feature:HoldOriginalFeature.values())holdFeatures.add(feature);}
    @Override protected void buildContent(){int y=0;addContentButton(50,y,"options.controls","screen.simple_translate.shortcuts.tooltip");y+=26;addContentTextButton(51,y,stateLabel("screen.simple_translate.hold_original.master_enabled",ModConfig.HOLD_ORIGINAL_ENABLED.get()),"screen.simple_translate.hold_original.master_enabled.tooltip");y+=34;for(ShortcutAction action:ShortcutAction.values()){addContentTextButton(100+action.ordinal(),y,label(action.translationKey(),action.chord(),captureAction==action),"screen.simple_translate.shortcuts.record.tooltip");y+=26;}y+=8;for(int i=0;i<holdFeatures.size();i++){HoldOriginalFeature feature=holdFeatures.get(i);addContentTextButton(200+i,y,label(feature.getTranslationKey(),feature.chord(),captureHold==feature),"screen.simple_translate.shortcuts.record.tooltip");y+=26;}setContentHeight(y+8);}
    @Override protected void drawContent(int x,int y){if(!status.isEmpty())drawContentText(status,0,0xFFCC66);}
    @Override protected boolean onContentButton(int id){if(id==50){Minecraft mc=Minecraft.getMinecraft();mc.displayGuiScreen(new GuiControls(this,mc.gameSettings));return false;}if(id==51){boolean enabled=!ModConfig.HOLD_ORIGINAL_ENABLED.get();ModConfig.HOLD_ORIGINAL_ENABLED.set(enabled);ModConfig.save();if(engine!=null)engine.setHoldOriginalEnabled(enabled);return true;}if(id>=100&&id<100+ShortcutAction.values().length){captureAction=ShortcutAction.values()[id-100];captureHold=null;status=tr("screen.simple_translate.shortcuts.press_chord");return true;}if(id>=200&&id<200+holdFeatures.size()){captureHold=holdFeatures.get(id-200);captureAction=null;status=tr("screen.simple_translate.shortcuts.press_chord");return true;}return false;}
    @Override protected void keyTyped(char typedChar,int keyCode)throws IOException{if(capturing()){if(keyCode==Keyboard.KEY_ESCAPE){clearCapture();return;}if(keyCode==Keyboard.KEY_DELETE||keyCode==Keyboard.KEY_BACK){store(KeyChord.NONE);return;}if(!KeyChord.isModifierKey(keyCode)){store(new KeyChord(KeyChord.InputType.KEYBOARD,keyCode,KeyChord.currentModifiers()));return;}}super.keyTyped(typedChar,keyCode);}
    @Override protected void mouseClicked(int mouseX,int mouseY,int mouseButton)throws IOException{if(capturing()&&mouseButton>=0){store(new KeyChord(KeyChord.InputType.MOUSE,mouseButton,KeyChord.currentModifiers()));return;}super.mouseClicked(mouseX,mouseY,mouseButton);}
    private boolean capturing(){return captureAction!=null||captureHold!=null;}
    private void store(KeyChord chord){if(conflicts(chord)){status=tr("screen.simple_translate.shortcuts.conflict");return;}if(captureAction!=null)ModConfig.getShortcutChord(captureAction).set(chord.serialize());else if(captureHold!=null)ModConfig.getHoldOriginalChord(captureHold).set(chord.serialize());ModConfig.save();status=tr("screen.simple_translate.shortcuts.saved");clearCapture();}
    private boolean conflicts(KeyChord chord){if(chord==null||!chord.isBound())return false;for(ShortcutAction action:ShortcutAction.values())if(action!=captureAction&&chord.equals(action.chord()))return true;for(HoldOriginalFeature feature:holdFeatures)if(feature!=captureHold&&chord.equals(feature.chord()))return true;return false;}
    private void clearCapture(){captureAction=null;captureHold=null;initGui();}
    private String label(String key,KeyChord chord,boolean capture){return tr(key)+": "+(capture?tr("screen.simple_translate.shortcuts.press_chord"):chord.displayName().getUnformattedText());}
}
