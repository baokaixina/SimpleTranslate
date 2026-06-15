package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.config.ModConfig.ApiFormat;
import com.yourname.simpletranslate.translation.TranslationService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/**
 * Compact model/API settings screen.
 */
public class ModelSettingsScreen extends ScrollableSettingsScreen {

    private String currentApiUrl;
    private String currentApiKey;
    private String currentModelId;
    private ApiFormat currentApiFormat;
    private int currentMaxParallelRequests;
    private String status = "";
    private boolean checking;

    private EditBox apiUrlInput;
    private EditBox apiKeyInput;
    private EditBox modelInput;
    private CycleButton<ApiFormat> formatButton;
    private CycleButton<Integer> maxParallelButton;
    private Button checkButton;

    public ModelSettingsScreen(Screen parent) {
        super(Component.translatable("screen.simple_translate.model_settings"), parent);
        this.currentApiUrl = ModConfig.DEEPSEEK_API_URL.get();
        this.currentApiKey = ModConfig.DEEPSEEK_API_KEY.get();
        this.currentModelId = ModConfig.DEEPSEEK_MODEL.get();
        this.currentApiFormat = ModConfig.API_FORMAT.get();
        this.currentMaxParallelRequests = ModConfig.API_MAX_PARALLEL_REQUESTS.get();
        this.contentWidth = 300;
    }

    @Override
    protected void buildContent() {
        addSectionHeader(text("screen.simple_translate.model_settings.section.connection"));

        this.apiUrlInput = new EditBox(this.font, 0, 0, this.contentWidth, 20,
                Component.translatable("screen.simple_translate.model_settings.api_url"));
        this.apiUrlInput.setMaxLength(512);
        this.apiUrlInput.setValue(this.currentApiUrl);
        this.apiUrlInput.setHint(Component.translatable("screen.simple_translate.model_settings.api_url_hint"));
        withTooltip(this.apiUrlInput, "screen.simple_translate.model_settings.api_url.tooltip");
        addEntry(this.apiUrlInput);

        this.apiKeyInput = new EditBox(this.font, 0, 0, this.contentWidth, 20,
                Component.translatable("screen.simple_translate.model_settings.api_key"));
        this.apiKeyInput.setMaxLength(512);
        this.apiKeyInput.setValue(this.currentApiKey);
        this.apiKeyInput.setHint(Component.translatable("screen.simple_translate.model_settings.api_key_hint"));
        withTooltip(this.apiKeyInput, "screen.simple_translate.model_settings.api_key.tooltip");
        this.apiKeyInput.addFormatter((value, pos) -> value.isEmpty()
                ? FormattedCharSequence.EMPTY
                : FormattedCharSequence.forward("*".repeat(value.length()), Style.EMPTY));
        addEntry(this.apiKeyInput);

        this.modelInput = new EditBox(this.font, 0, 0, this.contentWidth, 20,
                Component.translatable("screen.simple_translate.model_settings.model_id"));
        this.modelInput.setMaxLength(256);
        this.modelInput.setValue(this.currentModelId);
        this.modelInput.setHint(Component.translatable("screen.simple_translate.model_settings.model_hint"));
        withTooltip(this.modelInput, "screen.simple_translate.model_settings.model_id.tooltip");
        addEntry(this.modelInput);

        this.formatButton = CycleButton.<ApiFormat>builder(format -> Component.literal(format.getDisplayName()))
                .withValues(ApiFormat.values())
                .withInitialValue(this.currentApiFormat)
                .create(0, 0, this.contentWidth, 20,
                        Component.translatable("screen.simple_translate.model_settings.api_format"),
                        (button, value) -> {
                            this.currentApiFormat = value;
                            if (this.modelInput != null && (this.modelInput.getValue() == null || this.modelInput.getValue().isBlank())) {
                                this.modelInput.setValue(value.getDefaultModel());
                            }
                        });
        withTooltip(this.formatButton, "screen.simple_translate.model_settings.api_format.tooltip");
        addEntry(this.formatButton);

        this.maxParallelButton = CycleButton.<Integer>builder(value -> Component.literal(String.valueOf(value)))
                .withValues(1, 2, 3, 4, 5, 6, 7, 8)
                .withInitialValue(this.currentMaxParallelRequests)
                .create(0, 0, this.contentWidth, 20,
                        Component.translatable("screen.simple_translate.model_settings.max_parallel"),
                        (button, value) -> this.currentMaxParallelRequests = value);
        withTooltip(this.maxParallelButton, "screen.simple_translate.model_settings.max_parallel.tooltip");
        addEntry(this.maxParallelButton);

        Component checkLabel = this.checking
                ? Component.translatable("screen.simple_translate.model_settings.checking")
                : (this.status.isBlank()
                        ? Component.translatable("screen.simple_translate.model_settings.check")
                        : Component.literal(shortText(this.status)));
        this.checkButton = Button.builder(
                checkLabel,
                button -> checkApi())
                .bounds(0, 0, this.contentWidth, 20)
                .build();
        withTooltip(this.checkButton, this.status.isBlank()
                ? Component.translatable("screen.simple_translate.model_settings.check.tooltip")
                : Component.literal(this.status));
        addEntry(this.checkButton);
    }

    @Override
    protected void repositionEntries() {
        super.repositionEntries();
        boolean enabled = !this.checking;
        if (this.apiUrlInput != null) {
            this.apiUrlInput.active = this.apiUrlInput.visible && enabled;
        }
        if (this.apiKeyInput != null) {
            this.apiKeyInput.active = this.apiKeyInput.visible && enabled;
        }
        if (this.modelInput != null) {
            this.modelInput.active = this.modelInput.visible && enabled;
        }
        if (this.formatButton != null) {
            this.formatButton.active = this.formatButton.visible && enabled;
        }
        if (this.maxParallelButton != null) {
            this.maxParallelButton.active = this.maxParallelButton.visible && enabled;
        }
        if (this.checkButton != null) {
            this.checkButton.active = this.checkButton.visible && enabled;
        }
    }

    private void checkApi() {
        if (this.checking) {
            return;
        }
        captureInputs();
        if (this.currentApiKey.isBlank()) {
            this.status = text("screen.simple_translate.model_settings.status.no_key");
            rebuildScreen();
            return;
        }
        if (this.currentModelId.isBlank()) {
            this.status = text("screen.simple_translate.model_settings.status.no_model");
            rebuildScreen();
            return;
        }
        if (SimpleTranslateMod.getTranslationManager() == null) {
            this.status = text("screen.simple_translate.model_settings.status.manager_missing");
            rebuildScreen();
            return;
        }

        this.checking = true;
        this.status = text("screen.simple_translate.model_settings.checking");
        rebuildScreen();
        SimpleTranslateMod.getTranslationManager()
                .verifyModelAccess(this.currentApiKey, this.currentApiUrl, this.currentModelId, this.currentApiFormat)
                .thenAccept(result -> Minecraft.getInstance().execute(() -> finishCheck(result)))
                .exceptionally(error -> {
                    Minecraft.getInstance().execute(() -> {
                        this.checking = false;
                        this.status = text("screen.simple_translate.model_settings.status.failed",
                                shortText(error == null ? "unknown" : error.getMessage()));
                        rebuildScreen();
                    });
                    return null;
                });
    }

    private void finishCheck(TranslationService.ModelAccessResult result) {
        captureInputs();
        this.checking = false;
        if (result != null && result.success()) {
            this.status = text("screen.simple_translate.model_settings.status.available");
        } else {
            this.status = text("screen.simple_translate.model_settings.status.failed",
                    shortText(result == null ? "unknown" : result.message()));
        }
        rebuildScreen();
    }

    private void rebuildScreen() {
        this.clearWidgets();
        this.init();
    }

    private void captureInputs() {
        if (this.apiUrlInput != null) {
            this.currentApiUrl = this.apiUrlInput.getValue() == null ? "" : this.apiUrlInput.getValue().trim();
        }
        if (this.apiKeyInput != null) {
            this.currentApiKey = this.apiKeyInput.getValue() == null ? "" : this.apiKeyInput.getValue().trim();
        }
        if (this.modelInput != null) {
            this.currentModelId = this.modelInput.getValue() == null ? "" : this.modelInput.getValue().trim();
        }
    }

    @Override
    protected void saveSettings() {
        captureInputs();
        ModConfig.DEEPSEEK_API_KEY.set(this.currentApiKey);
        ModConfig.DEEPSEEK_API_URL.set(ModConfig.normalizeApiUrl(this.currentApiUrl));
        ModConfig.API_FORMAT.set(this.currentApiFormat);
        ModConfig.API_MAX_PARALLEL_REQUESTS.set(this.currentMaxParallelRequests);
        ModConfig.DEEPSEEK_MODEL.set(this.currentApiFormat == ApiFormat.DEEPSEEK_CHAT
                ? ModConfig.normalizeDeepSeekModelId(this.currentModelId)
                : (this.currentModelId == null || this.currentModelId.isBlank()
                        ? this.currentApiFormat.getDefaultModel()
                        : this.currentModelId));
        ModConfig.DEEPSEEK_THINKING_ENABLED.set(false);
        SimpleTranslateMod.getLogger().info("Model settings saved format={} model={} maxParallel={}",
                this.currentApiFormat, ModConfig.DEEPSEEK_MODEL.get(), this.currentMaxParallelRequests);
    }

    private String text(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    private String shortText(String text) {
        if (text == null || text.isBlank()) {
            return "unknown";
        }
        String compact = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (this.currentApiKey != null && !this.currentApiKey.isBlank()) {
            compact = compact.replace(this.currentApiKey, "***");
        }
        return compact.length() > 64 ? compact.substring(0, 61) + "..." : compact;
    }
}
