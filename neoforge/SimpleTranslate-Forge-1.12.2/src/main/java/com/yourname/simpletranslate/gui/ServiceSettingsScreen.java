package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.translation.TranslationEngine;
import com.yourname.simpletranslate.SimpleTranslateForge1122;
import com.yourname.simpletranslate.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;


/** Service credentials and request endpoint, applied as fields are edited. */
final class ServiceSettingsScreen extends ScrollableSettingsScreen {
    private GuiTextField endpoint;
    private GuiTextField apiKey;
    private GuiTextField model;
    private boolean checking;
    private String status = "";
    private Boolean statusSuccess;

    ServiceSettingsScreen(GuiScreen parent, TranslationEngine engine) {
        super(parent, engine, "screen.simple_translate.model_settings", "screen.simple_translate.main.translation_api");
    }

    @Override
    protected void buildContent() {
        addContentTextButton(90, 17, tr("screen.simple_translate.model_settings.api_format")+": "+
                ModConfig.API_FORMAT.get().getDisplayName(), "screen.simple_translate.model_settings.api_format.tooltip");
        endpoint = addTextField(0, 57, engine == null ? "" : engine.getEndpoint(), 512);
        apiKey = addMaskedTextField(1, 92, engine == null ? "" : engine.getApiKey(), 512);
        model = addTextField(2, 127, engine == null ? "" : engine.getModel(), 128);
        addContentTextButton(91,157,stateLabel("screen.simple_translate.thinking",ModConfig.DEEPSEEK_THINKING_ENABLED.get()),"screen.simple_translate.thinking.tooltip");
        addContentTextButton(92,183,tr("screen.simple_translate.model_settings.max_parallel")+": "+ModConfig.API_MAX_PARALLEL_REQUESTS.get(),"screen.simple_translate.model_settings.max_parallel.tooltip");
        String label = checking ? tr("screen.simple_translate.model_settings.checking")
                : tr("screen.simple_translate.model_settings.check");
        addContentTextButton(100, 209, label, "screen.simple_translate.model_settings.check.tooltip");
        setContentHeight(262);
    }

    @Override
    protected void drawContent(int mouseX, int mouseY) {
        if (!status.isEmpty()) {
            int color = checking ? 0xFFFFAA00 : (Boolean.TRUE.equals(statusSuccess) ? 0xFF55FF55 : 0xFFFF5555);
            drawContentText(status, 238, color);
        }
        drawContentText(tr("screen.simple_translate.model_settings.api_format"), 3, 0xFFFFFF);
        drawContentText(tr("screen.simple_translate.model_settings.api_url"), 45, 0xFFFFFF);
        drawContentText(tr("screen.simple_translate.model_settings.api_key"), 80, 0xFFFFFF);
        drawContentText(tr("screen.simple_translate.model_settings.model_id"), 115, 0xFFFFFF);
    }

    @Override
    protected void onFieldsChanged() {
        if (engine != null) {
            engine.updateConfiguration(endpoint.getText(), apiKey.getText(), model.getText(),
                    engine.getSourceLanguage(), engine.getTargetLanguage());
            if (!checking) { status = ""; statusSuccess = null; }
        }
    }

    @Override
    protected boolean onContentButton(int id) {
        if (engine == null) return false;
        if (id == 90) {
            ModConfig.ApiFormat[] formats = ModConfig.ApiFormat.values();
            int next = (ModConfig.API_FORMAT.get().ordinal() + 1) % formats.length;
            ModConfig.API_FORMAT.set(formats[next]);
            if (model.getText().trim().isEmpty()) model.setText(formats[next].getDefaultModel());
            ModConfig.save();
            SimpleTranslateForge1122.onTranslationSettingsChanged();
            onFieldsChanged();
            return true;
        }
        if (id == 91) {
            ModConfig.DEEPSEEK_THINKING_ENABLED.set(!ModConfig.DEEPSEEK_THINKING_ENABLED.get());
            ModConfig.save(); SimpleTranslateForge1122.onTranslationSettingsChanged();
            return true;
        }
        if(id==92){int next=ModConfig.API_MAX_PARALLEL_REQUESTS.get()>=8?1:ModConfig.API_MAX_PARALLEL_REQUESTS.get()+1;engine.setMaxParallelRequests(next);return true;}
        if (id != 100 || checking) return false;
        // All field changes have already been persisted. Reapply defensively in
        // case the player clicked Detect before moving focus away from a field.
        onFieldsChanged();
        checking = true;
        if(ModConfig.API_FORMAT.get()!=ModConfig.ApiFormat.LOCAL_OLLAMA&&apiKey.getText().trim().isEmpty()){checking=false;statusSuccess=Boolean.FALSE;status=tr("screen.simple_translate.model_settings.status.no_key");return true;}
        if(model.getText().trim().isEmpty()){checking=false;statusSuccess=Boolean.FALSE;status=tr("screen.simple_translate.model_settings.status.no_model");return true;}
        status = tr("screen.simple_translate.model_settings.checking");
        statusSuccess = null;
        engine.verifyApiAccess().whenComplete(new java.util.function.BiConsumer<TranslationEngine.ApiCheckResult, Throwable>() {
            @Override public void accept(final TranslationEngine.ApiCheckResult result, final Throwable error) {
                Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                    @Override public void run() {
                        if (Minecraft.getMinecraft().currentScreen != ServiceSettingsScreen.this) return;
                        checking = false;
                        statusSuccess = Boolean.valueOf(error == null && result != null && result.isAvailable());
                        status = statusSuccess.booleanValue()
                                ? tr("screen.simple_translate.model_settings.status.available")
                                : tr("screen.simple_translate.model_settings.status.failed",
                                result == null ? "request_failed" : result.getStatus());
                        initGui();
                    }
                });
            }
        });
        return true;
    }
}
